package com.nulabinc.backlog.migration.common.interpreters

import com.nulabinc.backlog.migration.common.domain._
import com.nulabinc.backlog.migration.common.domain.exports.{
  DeletedExportedBacklogStatus,
  ExistingExportedBacklogStatus
}
import monix.execution.Scheduler
import org.scalatest._

import java.nio.file.Paths

trait TestFixture {
  implicit val sc: Scheduler = monix.execution.Scheduler.global
  private val dbPath         = Paths.get("./test.db")

  val dsl = new SQLiteStoreDSL(dbPath)

  def setup(): Unit = {
    dbPath.toFile.delete()
    dsl.createTable.runSyncUnsafe()
  }
}

class SQLiteStoreDSLSpec
    extends funsuite.AnyFunSuite
    with matchers.must.Matchers
    with TestFixture {

  val defaultStatus = BacklogDefaultStatus(Id.backlogStatusId(2), BacklogStatusName("Open"), 999)
  val customStatus =
    BacklogCustomStatus(Id.backlogStatusId(1), BacklogStatusName("aaa"), 123, "color")

  test("store backlog status") {
    setup()

    dsl.storeBacklogStatus(customStatus).runSyncUnsafe()
    dsl.storeBacklogStatus(defaultStatus).runSyncUnsafe()
    dsl.findBacklogStatus(1).runSyncUnsafe() mustBe Some(customStatus)
    dsl.findBacklogStatus(2).runSyncUnsafe() mustBe Some(defaultStatus)
  }

  test("store backlog statuses") {
    setup()

    val statuses = BacklogStatuses(
      Seq(
        defaultStatus.copy(id = Id.backlogStatusId(3)),
        customStatus.copy(id = Id.backlogStatusId(4))
      )
    )
    dsl.storeBacklogStatus(statuses).runSyncUnsafe() mustBe ()
  }

  val existing         = ExistingExportedBacklogStatus(customStatus)
  val deleted          = DeletedExportedBacklogStatus(BacklogStatusName("bbb"))
  val exportedStatuses = Seq(existing, deleted)

  test("store exported status") {
    setup()

    dsl.storeSrcStatus(existing).runSyncUnsafe()
    dsl.storeSrcStatus(deleted).runSyncUnsafe()
    dsl.allSrcStatus.runSyncUnsafe() mustBe exportedStatuses
  }

  test("store exported statuses") {
    setup()

    dsl.storeSrcStatus(exportedStatuses).runSyncUnsafe()
    dsl.allSrcStatus.runSyncUnsafe().length mustBe 2
  }
}
