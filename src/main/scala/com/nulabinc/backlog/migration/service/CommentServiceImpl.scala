package com.nulabinc.backlog.migration.service

import java.io.{File, FileInputStream}
import javax.inject.Inject

import com.nulabinc.backlog.migration.conf.{BacklogConstantValue, BacklogPaths}
import com.nulabinc.backlog.migration.converter.Backlog4jConverters
import com.nulabinc.backlog.migration.domain._
import com.nulabinc.backlog.migration.utils.{ConsoleOut, Logging}
import com.nulabinc.backlog4j.CustomField.FieldType
import com.nulabinc.backlog4j.Issue.{PriorityType, ResolutionType, StatusType}
import com.nulabinc.backlog4j._
import com.nulabinc.backlog4j.api.option.{ImportUpdateIssueParams, QueryParams, UpdateIssueParams}
import com.nulabinc.backlog4j.internal.file.AttachmentDataImpl
import com.osinka.i18n.Messages

import scala.collection.JavaConverters._
import scalax.file.Path

/**
  * @author uchida
  */
class CommentServiceImpl @Inject()(backlog: BacklogClient, backlogPaths: BacklogPaths) extends CommentService with Logging {

  override def allCommentsOfIssue(issueId: Long): Seq[BacklogComment] = {
    val allCount = backlog.getIssueCommentCount(issueId)

    def loop(optMinId: Option[Long], comments: Seq[IssueComment], offset: Long): Seq[IssueComment] =
      if (offset < allCount) {
        val queryParams = new QueryParams()
        for { minId <- optMinId } yield {
          queryParams.minId(minId)
        }
        queryParams.count(100)
        queryParams.order(QueryParams.Order.Asc)
        val commentsPart =
          backlog.getIssueComments(issueId, queryParams).asScala
        val optLastId = for { lastComment <- commentsPart.lastOption } yield {
          lastComment.getId
        }
        loop(optLastId, comments union commentsPart, offset + 100)
      } else comments

    loop(None, Seq.empty[IssueComment], 0).sortWith((c1, c2) => c1.getCreated.before(c2.getCreated)).map(Backlog4jConverters.Comment.apply)
  }

  override def update(setUpdateParam: BacklogComment => ImportUpdateIssueParams)(backlogComment: BacklogComment): Either[Throwable, BacklogIssue] = {
    try {
      val result = updateIssue(setUpdateParam(backlogComment))
      logger.debug(s"    [Success Finish Create Comment]:${backlogComment.optIssueId}----------------------------")
      result
    } catch {
      case e: Throwable =>
        logger.debug(s"    [Fail Finish Create Comment]:${backlogComment.optCreated.getOrElse("")}----------------------------")
        Left(e)
    }
  }

  override def setUpdateParam(issueId: Long, path: Path, propertyResolver: PropertyResolver, toRemoteIssueId: (Long) => Option[Long])(
      backlogComment: BacklogComment): ImportUpdateIssueParams = {
    logger.debug(s"    [Start Create Comment][Comment Date]:${backlogComment.optCreated.getOrElse("")}")
    val params = new ImportUpdateIssueParams(issueId)

    //comment
    if (backlogComment.optContent.nonEmpty) {
      params.comment(backlogComment.optContent.getOrElse(""))
    }

    //notificationUserIds
    val notifiedUserIds = backlogComment.notifications.flatMap(_.optUser).flatMap(_.optUserId).flatMap(propertyResolver.optResolvedUserId)
    params.notifiedUserIds(notifiedUserIds.asJava)

    //created updated
    for { created <- backlogComment.optCreated } yield {
      params.created(created)
      params.updated(created)
    }

    //created updated user id
    for {
      createdUser <- backlogComment.optCreatedUser
      userId      <- createdUser.optUserId
      id          <- propertyResolver.optResolvedUserId(userId)
    } yield params.updatedUserId(id)

    //changelog
    backlogComment.changeLogs.map(setChangeLog) foreach (_(params, path, toRemoteIssueId, propertyResolver))

    params
  }

  private[this] def updateIssue(params: ImportUpdateIssueParams): Either[Throwable, BacklogIssue] =
    try {
      params.getParamList.asScala.foreach(p => logger.debug(s"        [Comment Parameter]:${p.getName}:${p.getValue}"))
      Right(Backlog4jConverters.Issue(backlog.importUpdateIssue(params)))
    } catch {
      case e: Throwable =>
        Left(e)
    }

  private[this] def setChangeLog(changeLog: BacklogChangeLog)(params: ImportUpdateIssueParams,
                                                              path: Path,
                                                              toRemoteIssueId: (Long) => Option[Long],
                                                              propertyResolver: PropertyResolver) = {
    if (changeLog.optAttributeInfo.nonEmpty) {
      setCustomField(params, changeLog, propertyResolver)
    } else if (changeLog.optAttachmentInfo.nonEmpty) {
      setAttachment(params, path, changeLog)
    } else setAttr(params, changeLog, toRemoteIssueId, propertyResolver)
  }

  private[this] def setAttr(params: ImportUpdateIssueParams,
                            changeLog: BacklogChangeLog,
                            toRemoteIssueId: (Long) => Option[Long],
                            propertyResolver: PropertyResolver) =
    changeLog.field match {
      case BacklogConstantValue.ChangeLog.SUMMARY         => setSummary(params, changeLog)
      case BacklogConstantValue.ChangeLog.DESCRIPTION     => setDescription(params, changeLog)
      case BacklogConstantValue.ChangeLog.COMPONENT       => setCategory(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.VERSION         => setVersion(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.MILESTONE       => setMilestone(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.STATUS          => setStatus(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.ASSIGNER        => setAssignee(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.ISSUE_TYPE      => setIssueType(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.START_DATE      => setStartDate(params, changeLog)
      case BacklogConstantValue.ChangeLog.LIMIT_DATE      => setDueDate(params, changeLog)
      case BacklogConstantValue.ChangeLog.PRIORITY        => setPriority(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.RESOLUTION      => setResolution(params, changeLog, propertyResolver)
      case BacklogConstantValue.ChangeLog.ESTIMATED_HOURS => setEstimatedHours(params, changeLog)
      case BacklogConstantValue.ChangeLog.ACTUAL_HOURS    => setActualHours(params, changeLog)
      case BacklogConstantValue.ChangeLog.PARENT_ISSUE    => setParentIssue(params, changeLog, toRemoteIssueId)
      case BacklogConstantValue.ChangeLog.NOTIFICATION    =>
    }

  private[this] def setSummary(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog) = {
    changeLog.optNewValue.map(value => params.summary(value))
  }

  private[this] def setDescription(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog) = {
    changeLog.optNewValue.map(value => params.description(value))
  }

  private[this] def setStartDate(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog) = {
    changeLog.optNewValue.map(value => params.startDate(value))
  }

  private[this] def setDueDate(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog) = {
    changeLog.optNewValue.map(value => params.dueDate(value))
  }

  private[this] def setCategory(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    changeLog.optNewValue match {
      case Some("") => params.categoryIds(null)
      case Some(value) =>
        val ids = value.split(",").toSeq.map(_.trim).flatMap(propertyResolver.optResolvedCategoryId)
        if (ids.nonEmpty) params.categoryIds(ids.asJava) else params.categoryIds(null)
      case None => params.categoryIds(null)
    }

  private[this] def setVersion(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    changeLog.optNewValue match {
      case Some("") => params.versionIds(null)
      case Some(value) =>
        val ids = value.split(",").toSeq.flatMap(propertyResolver.optResolvedVersionId)
        if (ids.nonEmpty) params.versionIds(ids.asJava) else params.versionIds(null)
      case None => params.versionIds(null)
    }

  private[this] def setMilestone(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    changeLog.optNewValue match {
      case Some("") => params.milestoneIds(null)
      case Some(value) =>
        val ids = value.split(",").toSeq.flatMap(propertyResolver.optResolvedVersionId)
        if (ids.nonEmpty) params.milestoneIds(ids.asJava) else params.milestoneIds(null)
      case None => params.milestoneIds(null)
    }

  private[this] def setStatus(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    (changeLog.optOriginalValue, changeLog.optNewValue) match {
      case (Some(originalValue), Some(newValue)) =>
        if (originalValue != newValue) {
          val originalStatusId = propertyResolver.tryResolvedStatusId(originalValue)
          val newStatusId      = propertyResolver.tryResolvedStatusId(newValue)
          if (!(originalStatusId == StatusType.Closed.getIntValue && newStatusId == StatusType.Open.getIntValue)) {
            params.status(StatusType.valueOf(newStatusId))
          }
        }
      case _ =>
        for { value <- changeLog.optNewValue } yield params.status(StatusType.valueOf(propertyResolver.tryResolvedStatusId(value)))
    }

  private[this] def setAssignee(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    changeLog.optNewValue match {
      case Some("") => params.assigneeId(null)
      case Some(value) =>
        for {
          id <- propertyResolver.optResolvedUserId(value)
        } yield params.assigneeId(id)
      case None => params.assigneeId(null)
    }

  private[this] def setIssueType(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    for { value <- changeLog.optNewValue } yield {
      propertyResolver.optResolvedIssueTypeId(value).map(params.issueTypeId)
    }

  private[this] def setPriority(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    for {
      value        <- changeLog.optNewValue
      priorityType <- propertyResolver.optResolvedPriorityId(value).map(value => PriorityType.valueOf(value.toInt))
    } yield params.priority(priorityType)

  private[this] def setResolution(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    for { value <- changeLog.optNewValue } yield {
      val optResolutionType = propertyResolver.optResolvedResolutionId(value).map(value => ResolutionType.valueOf(value.toInt))
      params.resolution(optResolutionType.getOrElse(ResolutionType.NotSet))
    }

  private[this] def setEstimatedHours(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog) =
    changeLog.optNewValue match {
      case Some("")    => params.estimatedHours(null)
      case Some(value) => params.estimatedHours(value.toFloat)
      case None        =>
    }

  private[this] def setActualHours(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog) =
    changeLog.optNewValue match {
      case Some("")    => params.actualHours(null)
      case Some(value) => params.actualHours(value.toFloat)
      case None        =>
    }

  private[this] def setParentIssue(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, toRemoteIssueId: (Long) => Option[Long]) =
    changeLog.optNewValue match {
      case Some(value) =>
        for { id <- toRemoteIssueId(value.toLong) } yield params.parentIssueId(id)
      case None =>
        params.parentIssueId(UpdateIssueParams.PARENT_ISSUE_NOT_SET)
    }

  private[this] def setAttachment(params: ImportUpdateIssueParams, path: Path, changeLog: BacklogChangeLog) =
    for {
      fileName <- changeLog.optAttachmentInfo.map(_.name)
      id       <- postAttachments(path, fileName)
    } yield params.attachmentIds(Seq(Long.box(id)).asJava)

  private[this] def postAttachments(path: Path, fileName: String): Option[Long] = {
    val files = backlogPaths.issueAttachmentDirectoryPath(path).toAbsolute.children()
    files.find(file => file.name == fileName) match {
      case Some(file) => postAttachment(file)
      case _          => None
    }
  }

  private[this] def postAttachment(path: Path): Option[Long] = {
    val optAttachment: Option[Attachment] =
      try {
        val file: File                     = new File(path.path)
        val attachmentData: AttachmentData = new AttachmentDataImpl(file.getName, new FileInputStream(file))
        Some(backlog.postAttachment(attachmentData))
      } catch {
        case e: Throwable =>
          logger.error(e.getMessage, e)
          if (e.getMessage.indexOf("The size of attached file is too large.") >= 0)
            ConsoleOut.println(Messages("import.error.attachment.too_large", path.name))
          else
            ConsoleOut.println(Messages("import.error.issue.attachment", path.name))
          None
      }
    optAttachment.map(_.getId)
  }

  private[this] def setCustomField(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, propertyResolver: PropertyResolver) =
    for { customFieldSetting <- propertyResolver.optResolvedCustomFieldSetting(changeLog.field) } yield {
      FieldType.valueOf(customFieldSetting.typeId) match {
        case FieldType.Text         => setTextCustomField(params, changeLog, customFieldSetting)
        case FieldType.TextArea     => setTextCustomFieldArea(params, changeLog, customFieldSetting)
        case FieldType.Numeric      => setNumericCustomField(params, changeLog, customFieldSetting)
        case FieldType.Date         => setDateCustomField(params, changeLog, customFieldSetting)
        case FieldType.SingleList   => setSingleListCustomField(params, changeLog, customFieldSetting)
        case FieldType.MultipleList => setMultipleListCustomField(params, changeLog, customFieldSetting)
        case FieldType.CheckBox     => setCheckBoxCustomField(params, changeLog, customFieldSetting)
        case FieldType.Radio        => setRadioCustomField(params, changeLog, customFieldSetting)
        case _                      =>
      }
    }

  private[this] def setTextCustomField(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, customFieldSetting: BacklogCustomFieldSetting) = {
    for {
      value <- changeLog.optNewValue
      id    <- customFieldSetting.optId
    } yield params.textCustomField(id, value)
  }

  private[this] def setTextCustomFieldArea(params: ImportUpdateIssueParams,
                                           changeLog: BacklogChangeLog,
                                           customFieldSetting: BacklogCustomFieldSetting) = {
    for {
      value <- changeLog.optNewValue
      id    <- customFieldSetting.optId
    } yield params.textAreaCustomField(id, value)
  }

  private[this] def setDateCustomField(params: ImportUpdateIssueParams, changeLog: BacklogChangeLog, customFieldSetting: BacklogCustomFieldSetting) =
    (changeLog.optNewValue, customFieldSetting.optId) match {
      case (Some(""), Some(id))    => params.dateCustomField(id, null)
      case (Some(value), Some(id)) => params.dateCustomField(id, value)
      case (None, Some(id))        => params.dateCustomField(id, null)
      case _                       => throw new RuntimeException
    }

  private[this] def setNumericCustomField(params: ImportUpdateIssueParams,
                                          changeLog: BacklogChangeLog,
                                          customFieldSetting: BacklogCustomFieldSetting) =
    (changeLog.optNewValue, customFieldSetting.optId) match {
      case (Some(""), Some(id))    => params.numericCustomField(id, null)
      case (Some(value), Some(id)) => params.numericCustomField(id, value.toFloat)
      case (None, Some(id))        => params.numericCustomField(id, null)
      case _                       => throw new RuntimeException
    }

  private[this] def setCheckBoxCustomField(params: ImportUpdateIssueParams,
                                           changeLog: BacklogChangeLog,
                                           customFieldSetting: BacklogCustomFieldSetting) =
    (changeLog.optNewValue, customFieldSetting.property, customFieldSetting.optId) match {
      case (Some(value), property: BacklogCustomFieldMultipleProperty, Some(id)) =>
        val newValues: Seq[String] = value.split(",").toSeq.map(_.trim)

        def findItem(newValue: String): Option[BacklogItem] = {
          property.items.find(_.name == newValue)
        }

        def isItem(value: String): Boolean = {
          findItem(value).isDefined
        }
        val listItems   = newValues.filter(isItem)
        val stringItems = newValues.filterNot(isItem)

        val itemIds = listItems.flatMap(findItem).flatMap(_.optId)
        params.checkBoxCustomField(id, itemIds.map(Long.box).asJava)
        params.customFieldOtherValue(id, stringItems.mkString(","))
      case _ =>
    }

  private[this] def setRadioCustomField(params: ImportUpdateIssueParams,
                                        changeLog: BacklogChangeLog,
                                        customFieldSetting: BacklogCustomFieldSetting) =
    (changeLog.optNewValue, customFieldSetting.property, customFieldSetting.optId) match {
      case (Some(value), property: BacklogCustomFieldMultipleProperty, Some(id)) if (value.nonEmpty) =>
        for {
          item   <- property.items.find(_.name == value)
          itemId <- item.optId
        } yield params.radioCustomField(id, itemId)
      case _ =>
    }

  private[this] def setSingleListCustomField(params: ImportUpdateIssueParams,
                                             changeLog: BacklogChangeLog,
                                             customFieldSetting: BacklogCustomFieldSetting) =
    (changeLog.optNewValue, customFieldSetting.property, customFieldSetting.optId) match {
      case (Some(value), property: BacklogCustomFieldMultipleProperty, Some(id)) if (value.nonEmpty) =>
        for {
          item   <- property.items.find(_.name == value)
          itemId <- item.optId
        } yield params.singleListCustomField(id, itemId)
      case _ =>
    }

  private[this] def setMultipleListCustomField(params: ImportUpdateIssueParams,
                                               changeLog: BacklogChangeLog,
                                               customFieldSetting: BacklogCustomFieldSetting) =
    (changeLog.optNewValue, customFieldSetting.property, customFieldSetting.optId) match {
      case (Some(value), property: BacklogCustomFieldMultipleProperty, Some(id)) =>
        val newValues: Seq[String] = value.split(",").toSeq.map(_.trim)

        def findItem(newValue: String): Option[BacklogItem] = {
          property.items.find(_.name == newValue)
        }

        def isItem(value: String): Boolean = {
          findItem(value).isDefined
        }
        val listItems   = newValues.filter(isItem)
        val stringItems = newValues.filterNot(isItem)

        val itemIds = listItems.flatMap(findItem).flatMap(_.optId)
        params.multipleListCustomField(id, itemIds.map(Long.box).asJava)
        params.customFieldOtherValue(id, stringItems.mkString(","))
      case _ =>
    }
}