package com.nulabinc.backlog.migration.common.service

import com.nulabinc.backlog.migration.common.client.BacklogAPIClient
import com.nulabinc.backlog.migration.common.domain.{BacklogProjectKey, BacklogStatus, BacklogStatuses}
import com.nulabinc.backlog4j.BacklogAPIException
import javax.inject.Inject

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * @author uchida
  */
class StatusServiceImpl @Inject()(backlog: BacklogAPIClient, projectKey: BacklogProjectKey) extends StatusService {

  override def allStatuses(): BacklogStatuses =
    Try {
      BacklogStatuses(
        backlog
          .getStatuses(projectKey)
          .asScala
          .toSeq
          .map(BacklogStatus.from)
      )
    }.recover {
      case ex: BacklogAPIException if ex.getMessage.contains("No such project") =>
        defaultStatuses()
      case ex =>
        throw ex
    }.getOrElse(defaultStatuses())

  private def defaultStatuses(): BacklogStatuses =
    BacklogStatuses(
      backlog
        .getStatuses
        .asScala
        .toSeq
        .map(BacklogStatus.from)
    )
}