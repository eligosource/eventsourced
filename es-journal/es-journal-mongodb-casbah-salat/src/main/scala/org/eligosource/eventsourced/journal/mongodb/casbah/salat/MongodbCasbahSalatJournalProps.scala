package org.eligosource.eventsourced.journal.mongodb.casbah.salat

import akka.actor._

import com.mongodb.casbah.Imports._
import org.eligosource.eventsourced.core._

/**
 * Configuration object for an Mongodb/Casbah based journal.
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.mongodb.casbah.MongodbCasbahJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journal: ActorRef = Journal(MongodbCasbahJournalProps(journalConn))
 * }}}
 *
 * @param mongoClient Required mongoDB/Casbah client.
 * @param dbName Required mongoDB database name.
 * @param eventCollectionName Required mongoDB collection name for events.
 * @param snapshotCollectionName Required mongoDB collection name for snapshots.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 */
case class MongodbCasbahSalatJournalProps(mongoClient: MongoClient,
    dbName: String,
    eventCollectionName: String,
    snapshotCollectionName: String,
    name: Option[String] = None,
    dispatcherName: Option[String] = None) extends JournalProps {

  val error = s"${this} with the same collections for events and snapshots is not allowed"
  require(eventCollectionName != snapshotCollectionName, error)

  /**
   * Returns a new `MongodbCasbahJournalProps` with specified journal actor name.
   */
  def withName(name: String) = copy(name = Some(name))

  /**
   * Returns a new `MongodbCasbahJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) = copy(dispatcherName = Some(dispatcherName))

  def journal: Actor = new MongodbCasbahSalatJournal(this)
}
