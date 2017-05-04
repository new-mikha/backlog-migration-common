package com.nulabinc.backlog.migration.convert.writes

import javax.inject.Inject

import com.nulabinc.backlog.migration.convert.{Convert, Writes}
import com.nulabinc.backlog.migration.domain.BacklogComment
import com.nulabinc.backlog.migration.utils.{DateUtil, Logging}
import com.nulabinc.backlog4j.IssueComment

import scala.collection.JavaConverters._

/**
  * @author uchida
  */
class CommentWrites @Inject()(implicit val changeLogWrites: ChangeLogWrites,
                              implicit val notificationWrites: NotificationWrites,
                              implicit val userWrites: UserWrites)
    extends Writes[IssueComment, BacklogComment]
    with Logging {

  override def writes(comment: IssueComment): BacklogComment = {
    BacklogComment(
      eventType = "comment",
      optIssueId = None,
      optContent = Option(comment.getContent),
      changeLogs = comment.getChangeLog.asScala.map(Convert.toBacklog(_)),
      notifications = comment.getNotifications.asScala.map(Convert.toBacklog(_)),
      isCreateIssue = false,
      optCreatedUser = Option(comment.getCreatedUser).map(Convert.toBacklog(_)),
      optCreated = Option(comment.getCreated).map(DateUtil.isoFormat)
    )
  }

}