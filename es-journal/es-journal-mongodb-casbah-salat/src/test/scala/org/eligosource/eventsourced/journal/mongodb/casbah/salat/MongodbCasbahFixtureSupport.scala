package org.eligosource.eventsourced.journal.mongodb.casbah.salat

import com.mongodb.casbah.Imports._
import org.eligosource.eventsourced.journal.mongodb.casbah.salat._

trait MongodbCasbahFixtureSupport {

  val dbName = "es1"
  val collName = "event"
  val snapshotName = "snapshot"
  val journalProps = MongodbCasbahSalatJournalProps(MongoClient(mongoLocalHostName, mongoDefaultPort), dbName, collName, snapshotName)

  def cleanup() {
    MongoClient(mongoLocalHostName, mongoDefaultPort)(dbName)(collName).dropCollection()
  }
}
