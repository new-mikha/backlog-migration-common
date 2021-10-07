package com.nulabinc.backlog.migration.importer.service

import better.files.{File => Path}
import cats.syntax.all._
import com.nulabinc.backlog.migration.common.conf.BacklogPaths
import com.nulabinc.backlog.migration.common.convert.BacklogUnmarshaller
import com.nulabinc.backlog.migration.common.domain.imports.ImportedIssueKeys
import com.nulabinc.backlog.migration.common.domain.{
  BacklogAttachment,
  BacklogComment,
  BacklogIssue,
  BacklogProject
}
import com.nulabinc.backlog.migration.common.dsl.{ConsoleDSL, StoreDSL}
import com.nulabinc.backlog.migration.common.service._
import com.nulabinc.backlog.migration.common.utils.{Logging, _}
import com.nulabinc.backlog.migration.importer.core.RetryException
import com.nulabinc.backlog4j.BacklogAPIException
import com.osinka.i18n.Messages
import monix.eval.Task
import monix.execution.Scheduler

/**
 * @author
 *   uchida
 */
private[importer] class IssuesImporter(
    backlogPaths: BacklogPaths,
    sharedFileService: SharedFileService,
    issueService: IssueService,
    commentService: CommentService,
    attachmentService: AttachmentService
) extends Logging {

  import com.nulabinc.backlog.migration.importer.core.RetryUtil._

  private[this] val console       = new IssueProgressBar()
  private[this] val retryInterval = 5000

  def execute(
      project: BacklogProject,
      propertyResolver: PropertyResolver,
      fitIssueKey: Boolean,
      retryCount: Int
  )(implicit s: Scheduler, storeDSL: StoreDSL[Task], consoleDSL: ConsoleDSL[Task]): Task[Unit] = {

    for {
      _ <- ConsoleDSL[Task].println("""
      :""".stripMargin)
    } yield {
      logger.warn("BLG_INTG-157 pass1")
      console.totalSize = totalSize()
      logger.warn("BLG_INTG-157 pass2")

      implicit val context =
        IssueContext(propertyResolver, fitIssueKey, retryCount)
      logger.warn("BLG_INTG-157 pass3")
      val paths = IOUtil.directoryPaths(backlogPaths.issueDirectoryPath).sortWith(_.name < _.name)
      logger.warn(s"BLG_INTG-157 pass4:${paths.size}")
      paths.foreach { path =>
        loadDateDirectory(project, path)
      }
    }

  }

  private[this] def loadDateDirectory(project: BacklogProject, path: Path)(implicit
      ctx: IssueContext,
      s: Scheduler,
      storeDSL: StoreDSL[Task],
      consoleDSL: ConsoleDSL[Task]
  ): Unit = {
    logger.warn("BLG_INTG-157 pass5")
    val jsonDirs =
      path.list.filter(_.isDirectory).toSeq.sortWith(compareIssueJsons)
    console.date = DateUtil.yyyymmddToSlashFormat(path.name)
    console.failed = 0

    logger.warn("BLG_INTG-157 pass6")
    jsonDirs.zipWithIndex.foreach {
      case (jsonDir, index) =>
        loadJson(project, jsonDir, index, jsonDirs.size)
    }
  }

  private[this] def loadJson(project: BacklogProject, path: Path, index: Int, size: Int)(implicit
      ctx: IssueContext,
      s: Scheduler,
      storeDSL: StoreDSL[Task],
      consoleDSL: ConsoleDSL[Task]
  ): Unit = {
    logger.warn("BLG_INTG-157 pass7")
    BacklogUnmarshaller.issue(backlogPaths.issueJson(path)) match {
      case Some(issue: BacklogIssue) =>
        logger.warn("BLG_INTG-157 pass8")
//        createTemporaryIssues(project, issue)
        logger.warn("BLG_INTG-157 pass9")
        retryBacklogAPIException(ctx.retryCount, retryInterval) {
          logger.warn("BLG_INTG-157 pass10")
          createIssue(project, issue, path, index, size)
        }
      case Some(comment: BacklogComment) =>
        logger.warn("BLG_INTG-157 pass11")
        createComment(comment, path, index, size)
      case _ =>
        logger.warn("BLG_INTG-157 pass12")
        None
    }
    logger.warn("BLG_INTG-157 pass13")
    console.count = console.count + 1
  }

  private def createIssue(
      project: BacklogProject,
      issue: BacklogIssue,
      path: Path,
      index: Int,
      size: Int
  )(implicit ctx: IssueContext, s: Scheduler, storeDSL: StoreDSL[Task]): Unit = {
    if (issueService.exists(project.id, issue)) {
      ctx.excludeIssueIds += issue.id
      for {
        remoteIssue <- issueService.optIssueOfParams(project.id, issue)
      } yield {
        storeDSL.storeImportedIssueKeys(
          ImportedIssueKeys(
            issue.id,
            issue.findIssueIndex,
            remoteIssue.id,
            remoteIssue.findIssueIndex
          )
        )
      }
      console.warning(
        index + 1,
        size,
        Messages(
          "import.issue.already_exists",
          issue.issueKey
        )
      )
    } else {
      issueService.create(
        issueService.setCreateParam(
          project.id,
          ctx.propertyResolver,
          ctx.toRemoteIssueId,
          postAttachment(path, index, size),
          issueService.issueOfId
        )
      )(issue) match {
        case Right(remoteIssue) =>
          sharedFileService.linkIssueSharedFile(remoteIssue.id, issue)
          ctx.addIssueId(issue, remoteIssue)
          storeDSL
            .storeImportedIssueKeys(
              ImportedIssueKeys(
                srcIssueId = issue.id,
                srcIssueIndex = issue.findIssueIndex,
                dstIssueId = remoteIssue.id,
                dstIssueIndex = remoteIssue.findIssueIndex
              )
            )
            .runSyncUnsafe()
        case _ =>
          console.failed += 1
      }
      console.progress(index + 1, size)
    }
  }

  private def createTemporaryIssues(
      project: BacklogProject,
      issue: BacklogIssue
  )(implicit
      ctx: IssueContext,
      s: Scheduler,
      storeDSL: StoreDSL[Task],
      consoleDSL: ConsoleDSL[Task]
  ): Unit = {
    logger.warn("BLG_INTG-157 pass 200")
    val issueIndex = issue.findIssueIndex
    logger.warn("BLG_INTG-157 pass 201")
    val prev       = storeDSL.getLatestImportedIssueKeys().runSyncUnsafe().srcIssueIndex
    logger.warn(s"BLG_INTG-157 pass 202: ctx.fitIssueKey:${ctx.fitIssueKey} prev:${prev} issueIndex:${issueIndex}")

    if (ctx.fitIssueKey && (prev + 1) != issueIndex) {
      val seq = (prev + 1) until issueIndex
      logger.warn(s"BLG_INTG-157 pass 203: seq:${seq}")

      seq.foreach { idx =>
        createTemporaryIssue(project, issue, idx)
      }
    }
  }

  private def createTemporaryIssue(
      project: BacklogProject,
      issue: BacklogIssue,
      dummyIndex: Int
  )(implicit
      ctx: IssueContext,
      s: Scheduler,
      storeDSL: StoreDSL[Task],
      consoleDSL: ConsoleDSL[Task]
  ) = {
    logger.warn("BLG_INTG-157 pass 204")
    val temporaryIssue = retryBacklogAPIException(ctx.retryCount, retryInterval) {
      logger.warn("BLG_INTG-157 pass 205")
      issueService.createDummy(project.id, ctx.propertyResolver)
    }

    logger.warn("BLG_INTG-157 pass 206")
    storeDSL
      .storeImportedIssueKeys(
        ImportedIssueKeys(
          srcIssueId = issue.id,
          srcIssueIndex = issue.findIssueIndex,
          dstIssueId = temporaryIssue.getId(),
          dstIssueIndex = BacklogIssue.getIssueIndex(temporaryIssue.getIssueKey())
        )
      )
      .runSyncUnsafe()

    logger.warn("BLG_INTG-157 pass 207")
    retryBacklogAPIException(ctx.retryCount, retryInterval) {
      logger.warn("BLG_INTG-157 pass 208")
      issueService.delete(temporaryIssue.getId)
    }
    logger.warn(
      s"${Messages("import.issue.create_dummy", s"${project.key}-${dummyIndex}")}"
    )

  }

  private[this] def createComment(
      comment: BacklogComment,
      path: Path,
      index: Int,
      size: Int
  )(implicit ctx: IssueContext, consoleDSL: ConsoleDSL[Task], s: Scheduler) = {

    def updateComment(remoteIssueId: Long): Unit = {

      val setUpdatedParam =
        retryBacklogAPIException(ctx.retryCount, retryInterval) {
          commentService.setUpdateParam(
            remoteIssueId,
            ctx.propertyResolver,
            ctx.toRemoteIssueId,
            postAttachment(path, index, size)
          ) _
        }

      try {
        retryBacklogAPIException(ctx.retryCount, retryInterval) {
          commentService.update(setUpdatedParam)(comment) match {
            case Left(e)
                if Option(e.getMessage)
                  .getOrElse("")
                  .contains("Please change the status or post a comment.") =>
              logger.warn(e.getMessage, e)
            case Left(e) =>
              throw e
            case Right(_) =>
              ()
          }
        }
      } catch {
        case e: RetryException =>
          handleUpdateCommentError(e, remoteIssueId)
        case e: BacklogAPIException =>
          handleUpdateCommentError(e, remoteIssueId)
        case e: Throwable =>
          throw e
      }
    }

    def handleUpdateCommentError(e: Throwable, remoteIssueId: Long): Unit = {
      logger.error(e.getMessage, e)
      val issue = issueService.issueOfId(remoteIssueId)
      console.error(
        index + 1,
        size,
        s"${Messages("import.error.failed.comment", issue.issueKey, e.getMessage)}"
      )
      console.failed += 1
    }

    def deleteAttachment(remoteIssueId: Long) =
      comment.changeLogs.filter { _.mustDeleteAttachment }.map { changeLog =>
        val issueAttachments =
          attachmentService.allAttachmentsOfIssue(remoteIssueId) match {
            case Right(attachments) => attachments
            case Left(_)            => Seq.empty[BacklogAttachment]
          }
        for {
          attachmentInfo <- changeLog.optAttachmentInfo
          attachment     <- issueAttachments.sortBy(_.optId).find(_.name == attachmentInfo.name)
          attachmentId   <- attachment.optId
          createdUser    <- comment.optCreatedUser
          createdUserId  <- createdUser.optUserId
          solvedCreatedUserId <- ctx.propertyResolver.optResolvedUserId(createdUserId)
          created             <- comment.optCreated
        } yield {
          issueService.deleteAttachment(
            remoteIssueId,
            attachmentId,
            solvedCreatedUserId,
            created
          )
        }
      }

    for {
      issueId       <- comment.optIssueId
      remoteIssueId <- ctx.toRemoteIssueId(issueId)
    } yield {
      if (!ctx.excludeIssueIds.contains(issueId)) {
        if (comment.changeLogs.exists(_.mustDeleteAttachment)) {
          deleteAttachment(remoteIssueId)
        } else {
          updateComment(remoteIssueId)
        }
        console.progress(index + 1, size)
      }
    }
  }

  private[this] val postAttachment = (path: Path, index: Int, size: Int) => { fileName: String =>
    {
      val files = backlogPaths.issueAttachmentDirectoryPath(path).list
      files.find(file => file.name == fileName) match {
        case Some(filePath) =>
          attachmentService.postAttachment(filePath.pathAsString) match {
            case Right(attachment) => attachment.optId
            case Left(e) =>
              if (
                e.getMessage.indexOf(
                  "The size of attached file is too large."
                ) >= 0
              )
                console.error(
                  index + 1,
                  size,
                  Messages("import.error.attachment.too_large", filePath.name)
                )
              else
                console.error(
                  index + 1,
                  size,
                  Messages(
                    "import.error.issue.attachment",
                    filePath.name,
                    e.getMessage
                  )
                )
              None
          }
        case _ => None

      }
    }
  }

  private[this] def compareIssueJsons(path1: Path, path2: Path): Boolean = {
    def getTimestamp(value: String): Long = value.split("-")(0).toLong

    def getIssueId(value: String): Long = value.split("-")(1).toLong

    def getType(value: String) = value.split("-")(2)

    def getIndex(value: String) = value.split("-")(3).toInt

    if (getTimestamp(path1.name) == getTimestamp(path2.name)) {
      if (getType(path1.name) == getType(path2.name))
        if (getIssueId(path1.name) == getIssueId(path2.name))
          getIndex(path1.name) < getIndex(path2.name)
        else getIssueId(path1.name) < getIssueId(path2.name)
      else getType(path1.name) > getType(path2.name)
    } else getTimestamp(path1.name) < getTimestamp(path2.name)
  }

  private[this] def totalSize(): Int = {
    val paths = IOUtil.directoryPaths(backlogPaths.issueDirectoryPath)
    paths.foldLeft(0) { (count, path) =>
      count + path.list.size
    }
  }

}
