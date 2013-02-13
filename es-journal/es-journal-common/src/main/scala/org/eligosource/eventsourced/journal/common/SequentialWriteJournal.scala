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
package org.eligosource.eventsourced.journal.common

import akka.actor._

import org.eligosource.eventsourced.core._

/**
 * Support trait for journal implementations with sequential, sync writes.
 */
trait SequentialWriteJournal extends Actor {
  import Channel.Deliver
  import Journal._

  private val deadLetters = context.system.deadLetters
  private var commandListener: Option[ActorRef] = None
  private var _counter = 0L

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
      executeBatchReplayInMsgs(replays, (msg, target) => target tell (Written(msg), deadLetters))
    }
    case cmd: ReplayInMsgs => {
      executeReplayInMsgs(cmd, msg => cmd.target tell (Written(msg), deadLetters))
    }
    case cmd: ReplayOutMsgs => {
      executeReplayOutMsgs(cmd, msg => cmd.target tell (Written(msg), deadLetters))
    }
    case BatchDeliverOutMsgs(channels) => {
      channels.foreach(_ ! Deliver)
      sender ! DeliveryDone
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
    _counter = storedCounter + 1L
  }

  /**
   * Calls `stop()`.
   */
  override def postStop() {
    stop()
  }

  /**
   * Returns the current counter value.
   */
  protected def counter = _counter

  /**
   * Returns the last stored counter value.
   */
  protected def storedCounter: Long

  /**
   * Instructs a journal provider to write an input message.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteInMsg]]
   */
  def executeWriteInMsg(cmd: WriteInMsg)

  /**
   * Instructs a journal provider to write an output message,
   * optionally together with an acknowledgement.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteInMsg]]
   */
  def executeWriteOutMsg(cmd: WriteOutMsg)

  /**
   * Instructs a journal provider to write an acknowledgement.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  def executeWriteAck(cmd: WriteAck)

  /**
   * Instructs a journal provider to delete an output message.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.DeleteOutMsg]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  def executeDeleteOutMsg(cmd: DeleteOutMsg)

  /**
   * Instructs a journal provider to batch-replay input messages. The provider must
   * reply to the sender with [[org.eligosource.eventsourced.core.Journal.ReplayDone]]
   * when all replayed messages have been added to the corresponding replay targets'
   * mailboxes.
   *
   * @param cmds command batch to be executed by the journal provider.
   * @param p function to be called by the provider for every replayed input message.
   *        The `acks` field of a replayed input message must contain the channel ids
   *        of all acknowledgements for that input message. The replay `target` of the
   *        currently processed [[org.eligosource.eventsourced.core.Journal.ReplayInMsgs]]
   *        command must be passed as second argument.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit)

  /**
   * Instructs a journal provider to replay input messages. The provider must
   * reply to the sender with [[org.eligosource.eventsourced.core.Journal.ReplayDone]]
   * when all replayed messages have been added to the corresponding replay target's
   * mailbox.
   *
   * @param cmd command to be executed by the journal provider.
   * @param p function to be called by the provider for each replayed input message.
   *        The `acks` field of a replayed input message must contain the channel ids
   *        of all acknowledgements for that input message.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
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

}
