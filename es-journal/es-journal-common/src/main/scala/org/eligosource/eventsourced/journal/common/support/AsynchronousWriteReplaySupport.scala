/*
 * Copyright 2012-2013 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.journal.common.support

import scala.concurrent._
import scala.util._

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.journal.common.JournalProps

trait AsynchronousWriteReplaySupport extends Actor {
  import AsynchronousWriteReplaySupport._

  private val deadLetters = context.system.deadLetters

  private lazy val w = writer
  private lazy val r = replayer
  private lazy val s = snapshotter

  private var _counter = 0L
  private var _counterResequencer = 1L
  private var _resequencer: ActorRef = _

  private def counter = _counter
  private def counterResequencer = _counterResequencer

  protected def writer: Writer
  protected def replayer: Replayer
  protected def snapshotter: Snapshotter

  def journalProps: JournalProps

  import context.dispatcher

  def asyncWriteAndResequence[A](cmd: A, handler: A => Future[Any]) {
    val ctr = counterResequencer
    val sdr = sender
    val write = handler(cmd)
    write onSuccess { case _ => _resequencer tell ((ctr, cmd), sdr) }
    write onFailure { case t => _resequencer tell ((ctr, WriteFailed(cmd, t)), sdr) }
    _counterResequencer += 1L
  }

  def asyncResequence(cmd: Any, inc: Long = 1L) {
    _resequencer forward (counterResequencer, cmd)
    _counterResequencer += inc
  }

  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      asyncWriteAndResequence(c.withTimestamp, w.executeWriteInMsg)
      _counter += 1L
    }
    case cmd: WriteOutMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      asyncWriteAndResequence(c, w.executeWriteOutMsg)
      _counter += 1L
    }
    case cmd: WriteAck => {
      asyncWriteAndResequence(cmd, w.executeWriteAck)
    }
    case cmd: DeleteOutMsg => {
      asyncWriteAndResequence(cmd, w.executeDeleteOutMsg)
    }
    case cmd: Loop => {
      asyncResequence(cmd)
    }
    case cmd: BatchReplayInMsgs => {
      asyncResequence(toSequenceNrLimit(cmd, counter - 1L), 2L)
    }
    case cmd: ReplayInMsgs => {
      asyncResequence(toSequenceNrLimit(cmd, counter - 1L), 2L)
    }
    case cmd: ReplayOutMsgs => {
      asyncResequence(toSequenceNrLimit(cmd, counter - 1L), 2L)
    }
    case cmd: BatchDeliverOutMsgs => {
      asyncResequence(cmd)
    }
    case cmd: RequestSnapshot => {
      asyncResequence(InternalRequestSnapshot(cmd, counter - 1L, self))
    }
    case SaveSnapshotDone(metadata, initiator) => {
      s.snapshotSaved(metadata)
      initiator ! metadata
    }
    case SaveSnapshotFailed(cause, initiator) => {
      initiator ! Status.Failure(cause)
    }
    case SaveSnapshot(snapshot) => {
      val initiator = sender
      s.saveSnapshot(snapshot.withTimestamp) onComplete {
        case Success(s) => self ! SaveSnapshotDone(s, initiator)
        case Failure(e) => self ! SaveSnapshotFailed(e, initiator)
      }
    }
    case InternalLoadSnapshot(processorId, snapshotFilter, promise) => {
      promise.completeWith(s.loadSnapshot(processorId, snapshotFilter))
    }
    case cmd: SetCommandListener => {
      _resequencer ! cmd
    }
  }

  override def preStart() {
    start()
    _counter = storedCounter + 1L
    _resequencer = actor(new Resequencer(counter, r), dispatcherName = journalProps.dispatcherName)
  }

  override def postStop() {
    stop()
  }

  def start() {}
  def stop() {}

  def storedCounter: Long

  trait Writer {
    def executeWriteInMsg(cmd: WriteInMsg): Future[Any]
    def executeWriteOutMsg(cmd: WriteOutMsg): Future[Any]
    def executeWriteAck(cmd: WriteAck): Future[Any]
    def executeDeleteOutMsg(cmd: DeleteOutMsg): Future[Any]
  }

  trait Replayer {
    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sdr: ActorRef): Future[Any]
    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sdr: ActorRef): Future[Any]
    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sdr: ActorRef): Future[Any]
  }

  trait Snapshotter {
    def loadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Future[Option[Snapshot]]
    def saveSnapshot(snapshot: Snapshot): Future[SnapshotSaved]
    def snapshotSaved(metadata: SnapshotMetadata)
  }

  class Resequencer(initialCounter: Long, replayer: Replayer) extends Actor {
    import scala.collection.mutable.Map

    private val delayed = Map.empty[Long, (Any, ActorRef)]
    private var delivered = 0L
    private var commandListener: Option[ActorRef] = None

    def receive = {
      case (seqnr: Long, cmd) => resequence(seqnr, cmd, sender)
      case SetCommandListener(cl) => commandListener = cl
    }

    def execute(cmd: Any, sdr: ActorRef, seqnr: Long) = cmd match {
      case c: WriteInMsg => {
        c.target tell (Written(c.message), sdr)
        commandListener.foreach(_ ! cmd)
      }
      case c: WriteOutMsg => {
        c.target tell (Written(c.message), sdr)
        commandListener.foreach(_ ! cmd)
      }
      case c: WriteAck => {
        commandListener.foreach(_ ! cmd)
      }
      case c: DeleteOutMsg => {
        commandListener.foreach(_ ! cmd)
      }
      case Loop(msg, target) => {
        target tell (Looped(msg), sdr)
      }
      case cmd @ BatchReplayInMsgs(replays) => {
        val ftr = for {
          cs <- Future.sequence(replays.map(offerSnapshot(_)))
          r  <- replayer.executeBatchReplayInMsgs(cs, (msg, target) => target tell (Written(msg), deadLetters), sdr)
        } yield r

        ftr onComplete {
          case Success(_) => { self ! (seqnr + 1L, ReplayDone); sdr ! ReplayDone }
          case Failure(e) => { self ! (seqnr + 1L, ReplayFailed(cmd, e)) }
        }
      }
      case cmd: ReplayInMsgs => {
        val ftr = for {
          c <- offerSnapshot(cmd)
          r <- replayer.executeReplayInMsgs(c, msg => c.target tell (Written(msg), deadLetters), sdr)
        } yield r

        ftr onComplete {
          case Success(_) => { self ! (seqnr + 1L, ReplayDone); sdr ! ReplayDone }
          case Failure(e) => { self ! (seqnr + 1L, ReplayFailed(cmd, e)) }
        }
      }
      case cmd: ReplayOutMsgs => {
        replayer.executeReplayOutMsgs(cmd, resetPromiseActorRef(initialCounter)(msg => cmd.target tell (Written(msg), deadLetters)), sdr) onComplete {
          case Success(_) => self ! (seqnr + 1L, ReplayDone)
          case Failure(e) => self ! (seqnr + 1L, ReplayFailed(cmd, e))
        }
      }
      case BatchDeliverOutMsgs(channels) => {
        channels.foreach(_ ! Deliver)
        sdr ! DeliveryDone
      }
      case InternalRequestSnapshot(RequestSnapshot(processorId, target), lastSequenceNr, journal) => {
        target.tell(SnapshotRequest(processorId, lastSequenceNr, sdr), journal)
      }
      case ReplayDone => {
        // nothing to do ...
      }
      case e: ReplayFailed => {
        context.system.eventStream.publish(e)
      }
      case e: WriteFailed => {
        context.system.eventStream.publish(e)
      }
    }

    private def loadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Future[Option[Snapshot]] = {
      val promise = Promise[Option[Snapshot]]
      context.parent ! InternalLoadSnapshot(processorId, snapshotFilter, promise)
      promise.future
    }

    private def offerSnapshot(cmd: ReplayInMsgs): Future[ReplayInMsgs] = {
      if (cmd.params.snapshot) loadSnapshot(cmd.processorId, cmd.params.snapshotFilter).map { snapshotOption =>
          snapshotOption match {
          case None    => cmd
          case Some(s) => {
            cmd.target ! SnapshotOffer(s)
            ReplayInMsgs(ReplayParams(cmd.processorId, s.sequenceNr + 1L, cmd.toSequenceNr), cmd.target)
          }
        }
      } else Future.successful(cmd)
    }

    @scala.annotation.tailrec
    private def resequence(seqnr: Long, cmd: Any, sdr: ActorRef) {
      if (seqnr == delivered + 1) {
        delivered = seqnr
        execute(cmd, sdr, seqnr)
      } else {
        delayed += (seqnr -> (cmd, sender))
      }
      val eo = delayed.remove(delivered + 1)
      if (eo.isDefined) resequence(delivered + 1, eo.get._1, eo.get._2)
    }
  }
}

private [journal] object AsynchronousWriteReplaySupport {
  case class WriteFailed(cmd: Any, cause: Throwable)
  case class ReplayFailed(replayCmd: Any, cause: Throwable)

  case class InternalRequestSnapshot(cmd: RequestSnapshot, lastSequenceNr: Long, journal: ActorRef)
  case class InternalLoadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean, promise: Promise[Option[Snapshot]])

  def toSequenceNrLimit(cmd: ReplayOutMsgs, limit: Long): ReplayOutMsgs =
    if (cmd.toSequenceNr > limit) cmd.copy(toSequenceNr = limit) else cmd

  def toSequenceNrLimit(cmd: ReplayInMsgs, limit: Long): ReplayInMsgs =
    if (cmd.params.toSequenceNr > limit) cmd.copy(params = cmd.params.withToSequenceNr(limit)) else cmd

  def toSequenceNrLimit(cmd: BatchReplayInMsgs, limit: Long): BatchReplayInMsgs =
    cmd.copy(replays = cmd.replays.map(toSequenceNrLimit(_, limit)))
}
