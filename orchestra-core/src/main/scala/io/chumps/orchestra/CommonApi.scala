package io.chumps.orchestra

import java.io.IOException
import java.time.Instant

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import io.circe._
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.shapes._
import io.circe.java8.time._
import org.scalajs.dom.ext.Ajax
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.searches.sort.SortOrder
import shapeless.HNil

import io.chumps.orchestra.kubernetes.Kubernetes
import io.chumps.orchestra.model.Indexed._
import io.chumps.orchestra.model.{Page, RunId, RunInfo}

trait CommonApi {
  def logs(runId: RunId, page: Page[Instant]): Seq[LogLine]
  def runningJobs(): Seq[Run[HNil, Unit]]
}

object CommonApi {
  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

  val client = new autowire.Client[String, Decoder, Encoder] {

    override def doCall(req: Request): Future[String] =
      Ajax
        .post(
          url = (Jobs.apiSegment +: Jobs.commonSegment +: req.path).mkString("/"),
          data = req.args.asJson.noSpaces,
          responseType = "application/json",
          headers = Map("Content-Type" -> "application/json")
        )
        .map(_.responseText)

    override def read[T: Decoder](raw: String) = decode[T](raw).fold(throw _, identity)
    override def write[T: Encoder](obj: T) = obj.asJson.noSpaces
  }.apply[CommonApi]

}

object CommonApiServer extends CommonApi {
  import io.chumps.orchestra.utils.AkkaImplicits._

  override def logs(runId: RunId, page: Page[Instant]): Seq[LogLine] = Await.result(
    Elasticsearch.client
      .execute(
        search(LogsIndex.index)
          .query(boolQuery.filter(termQuery("runId", runId.value.toString)))
          .sortBy(fieldSort("loggedOn").order(if (page.size < 0) SortOrder.Desc else SortOrder.Asc),
                  fieldSort("_id").order(SortOrder.Desc))
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
      .map(_.fold(failure => throw new IOException(failure.error.reason), identity).result.to[LogLine]),
    1.minute
  )

  override def runningJobs(): Seq[Run[HNil, Unit]] = Await.result(
    for {
      runInfos <- Kubernetes.client.jobs
        .namespace(OrchestraConfig.namespace)
        .list()
        .map(_.items.map(RunInfo.fromKubeJob))

      runs <- if (runInfos.nonEmpty)
        Elasticsearch.client
          .execute(
            search(HistoryIndex.index)
              .query(
                boolQuery.filter(termsQuery("runInfo.runId", runInfos.map(_.runId.value)),
                                 termsQuery("runInfo.jobId", runInfos.map(_.jobId.value)))
              )
              .sortBy(fieldSort("triggeredOn").desc(), fieldSort("_id").desc())
              .size(1000)
          )
          .map(_.fold(failure => throw new IOException(failure.error.reason), identity).result.to[Run[HNil, Unit]])
      else Future.successful(Seq.empty)
    } yield runs,
    1.minute
  )
}