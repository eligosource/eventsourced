package org.eligosource.eventsourced.journal.mongodb.casbah.salat

import org.scalatest.BeforeAndAfterEach
import com.mongodb.casbah.Imports._
import org.eligosource.eventsourced.journal.mongodb.casbah.salat._
import org.eligosource.eventsourced.core.PersistentReplaySpec2

class MongodbCasbahSalatReplaySpec extends PersistentReplaySpec2 with MongodbSpecSupport with BeforeAndAfterEach {

  val dbName = "es2"
  val collName = "event"
  val snapshotName = "snapshot"

  // Since multiple embedded instances will run, each one must have a different port.
  override def mongoPort = 50012

  def journalProps = MongodbCasbahSalatJournalProps(MongoClient(mongoLocalHostName, mongoPort), dbName, collName, snapshotName)

  override def afterEach() {
    MongoClient(mongoLocalHostName, mongoPort)(dbName)(collName).dropCollection()
    MongoClient(mongoLocalHostName, mongoPort)(dbName)(snapshotName).dropCollection()
  }
}