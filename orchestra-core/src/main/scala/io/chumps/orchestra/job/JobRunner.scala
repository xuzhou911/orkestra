package io.chumps.orchestra.job

import java.io.IOException
import java.time.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

import akka.http.scaladsl.server.Route
import autowire.Core
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import io.k8s.api.core.v1.PodSpec
import shapeless._
import shapeless.ops.function.FnToProduct
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.circe._
import io.circe.generic.auto._
import io.circe.java8.time._

import io.chumps.orchestra.board.Job
import io.chumps.orchestra.filesystem.Directory
import io.chumps.orchestra.kubernetes.JobUtils
import io.chumps.orchestra.model._
import io.chumps.orchestra.model.Indexed._
import io.chumps.orchestra.utils.Utils
import io.chumps.orchestra.utils.BaseEncoders._
import io.chumps.orchestra.utils.AkkaImplicits._
import io.chumps.orchestra.{AutowireServer, CommonApiServer, Elasticsearch}

case class JobRunner[ParamValues <: HList: Encoder: Decoder, Result: Encoder: Decoder](
  job: Job[ParamValues, Result, _, _],
  podSpec: ParamValues => PodSpec,
  func: ParamValues => Result
) {

  private[orchestra] def run(runInfo: RunInfo): Future[Result] = {
    val runningPong = system.scheduler.schedule(0.second, 1.second) {
      Elasticsearch.client
        .execute(
          updateById(HistoryIndex.index.name, HistoryIndex.`type`, HistoryIndex.formatId(runInfo))
            .doc(Json.obj("latestUpdateOn" -> Instant.now().asJson))
        )
        .map(_.fold(failure => throw new IOException(failure.error.reason), identity))
    }

    (for {
      run <- Elasticsearch.client
        .execute(get(HistoryIndex.index, HistoryIndex.`type`, HistoryIndex.formatId(runInfo)))
        .map(
          _.fold(failure => throw new IOException(failure.error.reason), identity).result.to[Run[ParamValues, Result]]
        )

      _ = run.parentJob.foreach { parentJob =>
        system.scheduler.schedule(1.second, 1.second) {
          if (!CommonApiServer.runningJobs().map(_.runInfo).contains(parentJob))
            failJob(runInfo, new InterruptedException(s"Parent job ${parentJob.jobId.value} stopped"))
              .transformWith(_ => JobUtils.delete(runInfo))
        }
      }

      result = Utils.elasticsearchOutErr(runInfo.runId) {
        println(s"Running job ${job.name}")
        val result =
          try func(run.paramValues)
          catch {
            case throwable: Throwable =>
              throwable.printStackTrace()
              throw throwable
          }
        println(s"Job ${job.name} completed")
        result
      }

      _ <- Elasticsearch.client
        .execute(
          updateById(HistoryIndex.index.name, HistoryIndex.`type`, HistoryIndex.formatId(runInfo))
            .doc(Json.obj("result" -> Option(Right(result): Either[Throwable, Result]).asJson))
            .retryOnConflict(1)
        )
        .map(_.fold(failure => throw new IOException(failure.error.reason), identity))
    } yield result)
      .recoverWith { case throwable => failJob(runInfo, throwable) }
      .transformWith { triedResult =>
        for {
          _ <- Future(runningPong.cancel())
          _ <- JobUtils.delete(runInfo)
          result <- Future.fromTry(triedResult)
        } yield result
      }
  }

  private[orchestra] object ApiServer extends job.Api {
    override def trigger(runId: RunId,
                         paramValues: ParamValues,
                         tags: Seq[String] = Seq.empty,
                         parent: Option[RunInfo] = None): Unit = Await.result(
      for {
        runInfo <- Future.successful(RunInfo(job.id, runId))
        _ <- Elasticsearch.client
          .execute(Elasticsearch.indexRun(runInfo, paramValues, tags, parent))
          .map(_.fold(failure => throw new IOException(failure.error.reason), identity))
        _ <- JobUtils.create(runInfo, podSpec(paramValues))
      } yield (),
      1.minute
    )

    override def stop(runId: RunId): Unit = JobUtils.delete(RunInfo(job.id, runId))

    override def tags(): Seq[String] = {
      val aggregationName = "tagsForJob"
      Await.result(
        Elasticsearch.client
          .execute(
            search(HistoryIndex.index)
              .query(boolQuery.filter(termQuery("runInfo.jobId", job.id.value)))
              .aggregations(termsAgg(aggregationName, "tags"))
              .size(1000)
          )
          .map(
            _.fold(failure => throw new IOException(failure.error.reason), identity).result.aggregations
              .terms(aggregationName)
              .buckets
              .map(_.key)
          ),
        1.minute
      )
    }

    override def history(page: Page[Instant]): History[ParamValues, Result] = Await.result(
      for {
        runs <- Elasticsearch.client
          .execute(
            search(HistoryIndex.index)
              .query(boolQuery.filter(termQuery("runInfo.jobId", job.id.value)))
              .sortBy(fieldSort("triggeredOn").desc(), fieldSort("_id").desc())
              .searchAfter(
                Seq(
                  page.after
                    .getOrElse(if (page.size < 0) Instant.now() else Instant.EPOCH)
                    .toEpochMilli: java.lang.Long,
                  ""
                )
              )
              .size(math.abs(page.size))
          )
          .map(
            _.fold(failure => throw new IOException(failure.error.reason), identity).result.hits.hits
              .flatMap(hit => Try(hit.to[Run[ParamValues, Result]]).toOption)
          )

        stages <- if (runs.nonEmpty)
          Elasticsearch.client
            .execute(
              search(StagesIndex.index)
                .query(boolQuery.filter(termsQuery("runInfo.runId", runs.toSeq.map(_.runInfo.runId.value)))) // TODO remove .toSeq when fixed in elastic4s
                .size(1000)
            )
            .map(_.fold(failure => throw new IOException(failure.error.reason), identity).result.to[Stage])
        else Future.successful(Seq.empty)
      } yield
        History(runs.map(run => (run, stages.filter(_.runInfo.runId == run.runInfo.runId).sortBy(_.startedOn))),
                Instant.now()),
      1.minute
    )
  }

  private def failJob(runInfo: RunInfo, throwable: Throwable) =
    Elasticsearch.client
      .execute(
        updateById(HistoryIndex.index.name, HistoryIndex.`type`, HistoryIndex.formatId(runInfo))
          .doc(Json.obj("result" -> Option(Left(throwable): Either[Throwable, Result]).asJson))
          .retryOnConflict(1)
      )
      .flatMap(_ => Future.failed(throwable))

  private[orchestra] val apiRoute: Route = {
    import akka.http.scaladsl.server.Directives._
    path(job.id.value / Segments) { segments =>
      entity(as[String]) { entity =>
        val body = AutowireServer.read[Map[String, String]](entity)
        val request = job.Api.router(ApiServer).apply(Core.Request(segments, body))
        onSuccess(request)(complete(_))
      }
    }
  }
}

object JobRunner {

  def apply[ParamValues <: HList, Result, Func, PodSpecFunc](job: Job[ParamValues, Result, Func, PodSpecFunc]) =
    new JobRunnerBuilder(job)

  class JobRunnerBuilder[ParamValues <: HList, Result, Func, PodSpecFunc](
    job: Job[ParamValues, Result, Func, PodSpecFunc]
  ) {
    def apply(func: Directory => Func)(implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
                                       encoderP: Encoder[ParamValues],
                                       decoderP: Decoder[ParamValues],
                                       encoderR: Encoder[Result],
                                       decoderR: Decoder[Result]) =
      JobRunner(job, (_: ParamValues) => PodSpec(Seq.empty), fnToProdFunc(func(Directory("."))))

    def apply(podSpec: PodSpec)(func: Directory => Func)(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      encoderR: Encoder[Result],
      decoderR: Decoder[Result]
    ) =
      JobRunner(job, (_: ParamValues) => podSpec, fnToProdFunc(func(Directory("."))))

    def apply(podSpecFunc: PodSpecFunc)(func: Directory => Func)(
      implicit fnToProdFunc: FnToProduct.Aux[Func, ParamValues => Result],
      fnToProdPodSpec: FnToProduct.Aux[PodSpecFunc, ParamValues => PodSpec],
      encoderP: Encoder[ParamValues],
      decoderP: Decoder[ParamValues],
      encoderR: Encoder[Result],
      decoderR: Decoder[Result]
    ) =
      JobRunner(job, fnToProdPodSpec(podSpecFunc), fnToProdFunc(func(Directory("."))))
  }
}