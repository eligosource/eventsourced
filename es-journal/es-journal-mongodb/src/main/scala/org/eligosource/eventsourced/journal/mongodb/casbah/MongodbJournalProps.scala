package org.eligosource.eventsourced.journal.mongodb.casbah

import akka.actor._

import com.mongodb.casbah.Imports._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common._

/**
 * Configuration object for an Mongodb based journal.
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.mongodb.MongodbJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journal: ActorRef = Journal(MongodbJournalProps(journalConn))
 * }}}
 *
 * @param journalColl Required Mongodb collection.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 */
case class MongodbJournalProps(journalColl: MongoCollection, name: Option[String] = None,
  dispatcherName: Option[String] = None) extends JournalProps {

  /**
   * Create unique index as ObjectId is used as "_id".
   */
  val indexes = MongoDBObject("processorId" -> 1, "initiatingChannelId" -> 1, "sequenceNr" -> 1,
    "confirmingChannelId" -> 1)

  val options = MongoDBObject("unique" -> true)
  journalColl.ensureIndex(indexes, options)

  /**
   * Returns a new `MongodbJournalProps` with specified journal actor name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `MongodbJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  def journal: Actor =
    new MongodbJournal(this)
}
