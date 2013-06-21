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

import scala.concurrent.Future
import scala.util._

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._

trait SynchronousWriteReplaySupport extends Actor {
  import context.dispatcher

  private val deadLetters = context.system.deadLetters
  private var commandListener: Option[ActorRef] = None

  private var _counter = 0L
  private var _counterInit = 0L

  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      val ct = c.withTimestamp
      executeWriteInMsg(ct)
      ct.target forward Written(ct.message)
      commandListener.foreach(_ ! cmd)
      _counter += 1L
    }
    case cmd: WriteOutMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      executeWriteOutMsg(c)
      c.target forward Written(c.message)
      commandListener.foreach(_ ! cmd)
      _counter += 1L
    }
    case cmd: WriteAck => {
      executeWriteAck(cmd)
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteOutMsg => {
      executeDeleteOutMsg(cmd)
      commandListener.foreach(_ ! cmd)
    }
    case Loop(msg, target) => {
      target forward (Looped(msg))
    }
    case BatchReplayInMsgs(replays) => {
      val cs = replays.map(offerSnapshot(_))
      executeBatchReplayInMsgs(cs, (msg, target) => target tell (Written(msg), deadLetters))
      sender ! ReplayDone
    }
    case cmd: ReplayInMsgs => {
      val c = offerSnapshot(cmd)
      executeReplayInMsgs(c, msg => c.target tell (Written(msg), deadLetters))
      sender ! ReplayDone
    }
    case cmd: ReplayOutMsgs => {
      executeReplayOutMsgs(cmd, resetPromiseActorRef(initialCounter)(msg => cmd.target tell (Written(msg), deadLetters)))
    }
    case BatchDeliverOutMsgs(channels) => {
      channels.foreach(_ ! Deliver)
      sender ! DeliveryDone
    }
    case RequestSnapshot(processorId, target) => {
      target ! SnapshotRequest(processorId, counter - 1L, sender)
    }
    case SaveSnapshotDone(metadata, initiator) => {
      snapshotSaved(metadata)
      initiator ! metadata
    }
    case SaveSnapshotFailed(cause, initiator) => {
      initiator ! Status.Failure(cause)
    }
    case SaveSnapshot(snapshot) => {
      val initiator = sender
      saveSnapshot(snapshot.withTimestamp) onComplete {
        case Success(s) => self ! SaveSnapshotDone(s, initiator)
        case Failure(e) => self ! SaveSnapshotFailed(e, initiator)
      }
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  /**
   * Initializes the `counter` from the last stored counter value and calls `start()`.
   */
  override def preStart() {
    start()
    _counterInit = storedCounter + 1L
    _counter = _counterInit
  }

  /**
   * Calls `stop()`.
   */
  override def postStop() {
    stop()
  }
  /**
   * Returns the initial counter value after journal start.
   */
  def initialCounter = _counterInit

  /**
   * Returns the current counter value.
   */
  protected def counter = _counter

  /**
   * Returns the last stored counter value.
   */
  protected def storedCounter: Long

  /**
   * Loads the latest snapshot for specified processor whose metadata match predicate `p`.
   *
   * @param processorId processor id of the snapshot.
   * @param snapshotFilter predicate for selecting saved snapshots.
   * @return youngest snapshots of those selected by `p`, if any.
   */
  def loadSnapshotSync(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Option[Snapshot]

  /**
   * Saves a snapshot asynchronously.
   *
   * @param snapshot a snapshot.
   * @return a future that is completed when the snapshot has been successfully saved.
   */
  def saveSnapshot(snapshot: Snapshot): Future[SnapshotSaved]

  /**
   * Called when a snapshot has been successfully saved.
   *
   * @param metadata snapshot metadata.
   */
  def snapshotSaved(metadata: SnapshotMetadata)

  /**
   * Instructs a journal provider to write an input message.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.JournalProtocol.WriteInMsg]]
   */
  def executeWriteInMsg(cmd: WriteInMsg)

  /**
   * Instructs a journal provider to write an output message,
   * optionally together with an acknowledgement.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.JournalProtocol.WriteInMsg]]
   */
  def executeWriteOutMsg(cmd: WriteOutMsg)

  /**
   * Instructs a journal provider to write an acknowledgement.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.JournalProtocol.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  def executeWriteAck(cmd: WriteAck)

  /**
   * Instructs a journal provider to delete an output message.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.JournalProtocol.DeleteOutMsg]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  def executeDeleteOutMsg(cmd: DeleteOutMsg)

  /**
   * Instructs a journal provider to batch-replay input messages.
   *
   * @param cmds command batch to be executed by the journal provider.
   * @param p function to be called by the provider for every replayed input message.
   *        The `acks` field of a replayed input message must contain the channel ids
   *        of all acknowledgements for that input message. The replay `target` of the
   *        currently processed [[org.eligosource.eventsourced.core.JournalProtocol.ReplayInMsgs]]
   *        command must be passed as second argument.
   *
   * @see [[org.eligosource.eventsourced.core.JournalProtocol.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit)

  /**
   * Instructs a journal provider to replay input messages.
   *
   * @param cmd command to be executed by the journal provider.
   * @param p function to be called by the provider for each replayed input message.
   *        The `acks` field of a replayed input message must contain the channel ids
   *        of all acknowledgements for that input message.
   *
   * @see [[org.eligosource.eventsourced.core.JournalProtocol.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit)

  /**
   * Instructs a journal provider to replay output messages.
   *
   * @param cmd command to be executed by the journal provider.
   * @param p function to be called by the provider for each replayed output message.
   *
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit)

  /**
   * Start callback. Empty default implementation.
   */
  protected def start() = {}

  /**
   * Stop callback. Empty default implementation.
   */
  protected def stop() = {}



  private def offerSnapshot(cmd: ReplayInMsgs): ReplayInMsgs = {
    if (cmd.params.snapshot) loadSnapshotSync(cmd.processorId, cmd.params.snapshotFilter) match {
      case Some(s) => {
        cmd.target ! SnapshotOffer(s)
        ReplayInMsgs(ReplayParams(cmd.processorId, s.sequenceNr + 1L, cmd.toSequenceNr), cmd.target)
      }
      case None => {
        cmd
      }
    } else cmd
  }
}
