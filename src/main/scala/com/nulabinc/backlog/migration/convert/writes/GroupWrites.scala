package com.nulabinc.backlog.migration.convert.writes

import javax.inject.Inject

import com.nulabinc.backlog.migration.convert.{Convert, Writes}
import com.nulabinc.backlog.migration.domain.BacklogGroup
import com.nulabinc.backlog.migration.utils.Logging
import com.nulabinc.backlog4j.Group

import scala.collection.JavaConverters._

/**
  * @author uchida
  */
class GroupWrites @Inject()(implicit val userWrites: UserWrites) extends Writes[Group, BacklogGroup] with Logging {

  override def writes(group: Group): BacklogGroup = {
    BacklogGroup(group.getName, group.getMembers.asScala.map(Convert.toBacklog(_)))
  }

}
