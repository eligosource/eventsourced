package org.eligosource.eventsourced.journal.mongodb.casbah.salat

import com.novus.salat.annotations.raw.Salat
import org.eligosource.eventsourced.core.{ Journal, Message, SnapshotMetadata, Snapshot }
import org.eligosource.eventsourced.core.Journal.{ WriteAck, WriteOutMsg, WriteInMsg }

@Salat
trait MongodbSnapshotState //we need to extend each case class snapshot explicitly from MongodbSnapshotState
@Salat
trait MongodbEvent //we need to extend each case class event explicitly from MongodbEvent

case class Key(processorId: Int, channelId: Int, fromSequenceNr: Long, toSequenceNr: Long)
case class MongodbSnapshot(processorId: Int, sequenceNr: Long, timestamp: Long, snapshotNr: Int, state: MongodbSnapshotState) extends SnapshotMetadata
case class MongodbMessage(processorId: Int, sequenceNr: Long, initiatingChannelId: Int, confirmingChannelId: Int, event: Option[MongodbEvent], msg: Option[Message])

object MongodbSnapshot {
  def apply(snapshot: Snapshot)(implicit maxSnapshotNr: Int) = snapshot match {
    case Snapshot(p, s, t, state: MongodbSnapshotState) => Some(List(new MongodbSnapshot(p, s, t, maxSnapshotNr + 1, state)))
    case Snapshot(p, s, t, states: List[_]) => Some(states collect {
      case state: MongodbSnapshotState => new MongodbSnapshot(p, s, t, maxSnapshotNr + 1, state)
    })
    case _ => None
  }
}

object MongodbMessage {
  def apply(cmd: WriteAck) = Some(List(new MongodbMessage(cmd.processorId, cmd.ackSequenceNr, 0, cmd.channelId, None, None)))
  def apply(cmd: WriteInMsg)(implicit sequenceNr: Long) = cmd.message.event match {
    case e: MongodbEvent => Some(List(new MongodbMessage(cmd.processorId, sequenceNr, 0, 0, Some(e), Some(clear(cmd.message)))))
    case _ => None
  }
  def apply(cmd: WriteOutMsg)(implicit sequenceNr: Long) = cmd.message.event match {
    case e: MongodbEvent => Some(new MongodbMessage(Int.MaxValue, sequenceNr, cmd.channelId, 0, Some(e), Some(clear(cmd.message))) :: (cmd.ackSequenceNr match {
      case Journal.SkipAck => Nil
      case _ => new MongodbMessage(cmd.ackProcessorId, cmd.ackSequenceNr, 0, cmd.channelId, None, None) :: Nil
    }))
    case _ => None
  }

  //TODO remove when migrating
  def clear(msg: Message) = msg.copy(
    posConfirmationTarget = null,
    posConfirmationMessage = null,
    negConfirmationTarget = null,
    negConfirmationMessage = null)
}
