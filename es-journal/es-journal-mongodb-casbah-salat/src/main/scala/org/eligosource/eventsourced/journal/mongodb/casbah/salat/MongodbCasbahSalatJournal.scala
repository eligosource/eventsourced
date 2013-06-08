package org.eligosource.eventsourced.journal.mongodb.casbah.salat

import org.eligosource.eventsourced.journal.common.SynchronousWriteReplaySupport
import org.eligosource.eventsourced.core._
import akka.event.Logging
import com.mongodb.casbah.Imports._
import akka.actor.ActorRef
import concurrent.Future
import org.eligosource.eventsourced.core.Snapshot
import org.eligosource.eventsourced.core.Message
import org.eligosource.eventsourced.core.SnapshotSaved
import com.novus.salat.dao.{SalatInsertError, SalatDAO}
import com.novus.salat.global._
import scala.Some

class MongodbCasbahSalatJournal(props: MongodbCasbahSalatJournalProps) extends SynchronousWriteReplaySupport {
  import Journal._

  val log = Logging(context.system, this.getClass)
  val all = MongoDBObject()

  var messageDAO: SalatDAO[MongodbMessage, ObjectId] = _
  var snapshotDAO: SalatDAO[MongodbSnapshot, ObjectId] = _
  implicit def maxSequenceNr = counter

  val snapshot_not_supported = s"Snapshot states of non <code>MongodbSnapshotState</code> type are not supported by ${this}"
  val message_event_not_supported = s"Events of non <code>MongodbEvent</code> type are not supported by ${this}"
  val empty_state = s"Snapshotting of empty <code>MongodbSnapshotState</code> is not allowed by ${this}"

  def executeWriteInMsg(cmd: WriteInMsg) { insertMessage(MongodbMessage(cmd)) }
  def executeWriteOutMsg(cmd: WriteOutMsg) { insertMessage(MongodbMessage(cmd)) }
  def executeWriteAck(cmd: WriteAck) { insertMessage(MongodbMessage(cmd)) }

  def insertMessage(messages: Option[List[MongodbMessage]]) = messages match {
    case Some(msgs) => messageDAO.insert(msgs, WriteConcern.Normal)
    case None => log.error(message_event_not_supported)
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    messageDAO.remove(MongoDBObject(
      "processorId"         -> Int.MaxValue,
      "initiatingChannelId" -> cmd.channelId,
      "sequenceNr"          -> cmd.msgSequenceNr,
      "confirmingChannelId" -> 0))
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => replay(Key(cmd.processorId, 0, cmd.fromSequenceNr, cmd.toSequenceNr), msg => p(msg, cmd.target)))
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    replay(Key(cmd.processorId, 0, cmd.fromSequenceNr, cmd.toSequenceNr), p)
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    replay(Key(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr, cmd.toSequenceNr), p)
  }

  def snapshotSaved(metadata: SnapshotMetadata) {}

  def storedCounter = {
    val vals = messageDAO.find(all).sort(MongoDBObject("sequenceNr" -> -1)).limit(1)
    if (vals.nonEmpty) vals.next().sequenceNr else 0L
  }

  implicit def maxSnapshotNr = {
    val vals = snapshotDAO.find(all).sort(MongoDBObject("snapshotNr" -> -1)).limit(1)
    if (vals.nonEmpty) vals.next().snapshotNr else 0
  }

  def loadSnapshot(processorId: Int, snapshotFilter: (SnapshotMetadata) => Boolean) = {
    @scala.annotation.tailrec
    def scanDB(implicit snapshotNr: Int): List[MongodbSnapshot] = {
      val query = MongoDBObject("processorId" -> processorId, "snapshotNr" -> snapshotNr)
      snapshotDAO.find(query).filter { snapshot => snapshotFilter(snapshot) } match {
        case snapshot if snapshot.nonEmpty || snapshotNr == 0 => snapshot.toList
        case _ => scanDB(snapshotNr - 1)
      }
    }
    scanDB match {
      case snapshot :: List() => Some(Snapshot(snapshot.processorId, snapshot.sequenceNr, snapshot.timestamp, snapshot.state))
      case snapshot :: tail => Some(Snapshot(snapshot.processorId, snapshot.sequenceNr, snapshot.timestamp, snapshot.state :: tail.map(_.state)))
      case _ => None
    }
  }

  def saveSnapshot(snapshot: Snapshot) = MongodbSnapshot(snapshot) match {
    case None => Future.failed(new SnapshotNotSupportedException(snapshot_not_supported))
    case Some(snapshots) => try {
      snapshotDAO.insert(snapshots, WriteConcern.Normal) match {
        case head :: tail => Future.successful(SnapshotSaved(snapshot.processorId, snapshot.sequenceNr, snapshot.timestamp))
        case Nil => Future.failed(new SnapshotNotSupportedException(empty_state))
      }
    } catch {
      case err: SalatInsertError => Future.failed(err)
    }
  }

  private def replay(key: Key, p: Message => Unit) {
    val query = MongoDBObject(
      "processorId"         -> key.processorId,
      "initiatingChannelId" -> key.channelId,
      "sequenceNr"          -> MongoDBObject(
        "$gte" -> key.fromSequenceNr,
        "$lte" -> key.toSequenceNr))

    val sortKey = MongoDBObject(
      "processorId"         -> 1,
      "sequenceNr"          -> 1,
      "initiatingChannelId" -> 1,
      "confirmingChannelId" -> 1)

    replay(messageDAO.find(query).sort(sortKey).toIterator, key, p)
  }

  private def replay(messages: Iterator[MongodbMessage], key: Key, p: Message => Unit) {
    val partition = messages.partition(m => m.confirmingChannelId == 0 && m.msg.isDefined)
    partition._1 foreach { m =>
      val acks = partition._2.takeWhile(_.sequenceNr == m.sequenceNr)
      p(m.msg.get.copy(event = m.event.get, acks = acks.map(_.confirmingChannelId).toSeq))
    }
  }

  override def start() {
    val snapshotCollection = props.mongoClient(props.dbName)(props.snapshotCollectionName)
    val messageCollection = props.mongoClient(props.dbName)(props.eventCollectionName)
    snapshotDAO = new SalatDAO[MongodbSnapshot, ObjectId](snapshotCollection) {}
    messageDAO = new SalatDAO[MongodbMessage, ObjectId](messageCollection) {}

    val indexes = MongoDBObject(
      "processorId"         -> 1,
      "initiatingChannelId" -> 1,
      "sequenceNr"          -> 1,
      "confirmingChannelId" -> 1)

    // Create index option for uniqueness. Required so we do not get duplicates.
    val options = MongoDBObject("unique" -> true)

    // Enforce unique index on collection of messages.
    messageCollection.ensureIndex(indexes, options)
  }

  override def stop() {
    props.mongoClient.close()
  }
}
