package com.drivetribe.orchestration.backend

import com.drivetribe.orchestration.infrastructure.Environment
import com.goyeau.orchestra.kubernetes.PodConfig
import com.goyeau.orchestra.{Job, _}
import com.typesafe.scalalogging.Logger

object SqlCopy {

  lazy val jobDefinition = Job[(String, String) => Unit](Symbol("sqlCopy"))

  lazy val job = jobDefinition(PodConfig(MySqlContainer))(apply _)

  lazy val board =
    SingleJobBoard("SQL Copy", jobDefinition)(
      // @TODO Use Environment instead of String
      Param[String]("Source Environment", defaultValue = Some(Environment.Staging.entryName)),
      Param[String]("Destination Environment")
    )

  lazy val logger = Logger(getClass)

  def apply(mySql: MySqlContainer.type)(source: String, destination: String): Unit =
    mySql.dump(Environment.withNameInsensitive(source), Environment.withNameInsensitive(destination))
}