package tech.orkestra.github

import scala.concurrent.Future

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.{Accepted, OK}
import akka.http.scaladsl.server.Directives.{entity, _}
import com.typesafe.scalalogging.Logger
import tech.orkestra.OrkestraPlugin
import tech.orkestra.utils.AkkaImplicits._
import io.circe.parser._

/**
  * Mix in this trait to get support for Github webhook triggers.
  */
trait GithubHooks extends OrkestraPlugin {
  private lazy val logger = Logger(getClass)

  def githubTriggers: Set[GithubTrigger]

  override def onMasterStart(): Future[Unit] =
    for {
      _ <- super.onMasterStart()
      _ = logger.info("Starting Github triggers webhook")

      routes = path("health") {
        complete(OK)
      } ~
        path("webhooks") {
          headerValueByName("X-GitHub-Event") { eventType =>
            entity(as[String]) { entity =>
              onSuccess(Future.traverse(githubTriggers)(_.trigger(eventType, parse(entity).fold(throw _, identity)))) {
                case triggers if triggers.contains(true) => complete(OK)
                case _                                   => complete(Accepted)

              }
            }
          }
        }

      _ = Http().bindAndHandle(routes, "0.0.0.0", GithubConfig.fromEnvVars().port)
    } yield ()
}
