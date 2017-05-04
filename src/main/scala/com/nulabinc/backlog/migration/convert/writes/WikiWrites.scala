package com.nulabinc.backlog.migration.convert.writes

import javax.inject.Inject

import com.nulabinc.backlog.migration.convert.{Convert, Writes}
import com.nulabinc.backlog.migration.domain.BacklogWiki
import com.nulabinc.backlog.migration.utils.{DateUtil, Logging}
import com.nulabinc.backlog4j.{Attachment, SharedFile, Wiki}

import scala.collection.JavaConverters._

/**
  * @author uchida
  */
class WikiWrites @Inject()(implicit val userWrites: UserWrites,
                           implicit val sharedFileWrites: SharedFileWrites,
                           implicit val attachmentWrites: AttachmentWrites)
    extends Writes[Wiki, BacklogWiki]
    with Logging {

  override def writes(wiki: Wiki): BacklogWiki = {
    if (getSharedFiles(wiki).nonEmpty) {
      logger.debug("[SharedFiles]issue shared files not empty.")
    }

    BacklogWiki(
      optId = Some(wiki.getId),
      name = wiki.getName,
      optContent = Option(wiki.getContent),
      attachments = getAttachments(wiki).map(Convert.toBacklog(_)),
      sharedFiles = getSharedFiles(wiki).map(Convert.toBacklog(_)),
      optCreatedUser = Option(wiki.getCreatedUser).map(Convert.toBacklog(_)),
      optCreated = Option(wiki.getCreated).map(DateUtil.isoFormat),
      optUpdatedUser = Option(wiki.getUpdatedUser).map(Convert.toBacklog(_)),
      optUpdated = Option(wiki.getUpdated).map(DateUtil.isoFormat)
    )
  }

  private[this] def getSharedFiles(wiki: Wiki): Seq[SharedFile] = {
    try {
      wiki.getSharedFiles.asScala
    } catch {
      case e: Throwable =>
        logger.warn(e.getMessage, e)
        Seq.empty[SharedFile]
    }
  }

  private[this] def getAttachments(wiki: Wiki): Seq[Attachment] = {
    try {
      wiki.getAttachments.asScala
    } catch {
      case e: Throwable =>
        logger.warn(e.getMessage, e)
        Seq.empty[Attachment]
    }
  }

}
