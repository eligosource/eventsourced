package org.eligosource.eventsourced.journal.mongodb.casbah

trait MongodbFixtureSupport {

  val journalColl = mongoConn(mongoDBName)(mongoDBColl)
  val journalProps = MongodbJournalProps(journalColl)

  def cleanup() {
    journalColl.dropCollection()
  }
}
