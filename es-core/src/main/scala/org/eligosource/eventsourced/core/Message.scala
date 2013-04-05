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
package org.eligosource.eventsourced.core

import akka.actor.ActorRef

/**
 * A message for communicating application events. Application events are not interpreted
 * by the [[https://github.com/eligosource/eventsourced eventsourced library]] and can have
 * any type. Since the library doesn't make any assumptions about the structure and semantics
 * of `event`, applications may also choose to send ''commands'' with [[org.eligosource.eventsourced.core.Message]]s.
 * In other words, the library can be used for both, event-sourcing and command-sourcing.
 *
 * Messages sent to an [[org.eligosource.eventsourced.core.Eventsourced]] processor
 * are called ''input'' messages. Processors process input messages and optionally
 * ''emit'' (or send) ''output'' messages to one or more destinations, usually via
 * [[org.eligosource.eventsourced.core.Channel]]s. Output messages should be derived
 * from input messages using the `copy(...)` method. Processors may also reply to
 * initial senders using the actor's current `sender` reference.
 *
 * @param event Application event (or command).
 * @param sequenceNr Sequence number that is generated when messages are written
 *        to the journal. Can also be used for detecting duplicates, in special cases.
 * @param timestamp time the input message was added to the event log.
 * @param processorId Id of the event processor that stored (and emitted) this message.
 * @param ack Whether or not an ''acknowledgement'' should be written to the journal during
 *        (or after) delivery of this message by a [[org.eligosource.eventsourced.core.Channel]].
 *        Used by event processors to indicate a series of output messages (that are derived
 *        from a single input message). In this case, output messages 1 to n-1 should have `ack`
 *        set to `false` and only output message n should have `ack` set to `true` (default).
 *        If an acknowledgement has been written for a series, all messages of that series will
 *        be ignored by the corresponding channel during a replay, otherwise all of them will
 *        be delivered again.
 */
case class Message(
  event: Any,
  sequenceNr: Long = 0L,
  timestamp: Long = 0L,
  processorId: Int = 0,
  acks: Seq[Int] = Nil,
  ack: Boolean = true,
  senderPath: String = null,
  posConfirmationTarget: ActorRef = null,
  posConfirmationMessage: Any = null,
  negConfirmationTarget: ActorRef = null,
  negConfirmationMessage: Any = null) {

  /**
   * Should be called by [[org.eligosource.eventsourced.core.Channel]] destinations to
   * (positively or negatively) confirm the receipt of this event message. Destinations
   * may also delegate this call other actors or threads.
   *
   * @param pos `true` for a positive receipt confirmation, `false` for a negative one.
   */
  def confirm(pos: Boolean = true) {
    if (pos) confirmPos() else confirmNeg()
  }

  private def confirmPos() = if (posConfirmationTarget ne null) posConfirmationTarget ! posConfirmationMessage
  private def confirmNeg() = if (negConfirmationTarget ne null) negConfirmationTarget ! negConfirmationMessage

  private [eventsourced] def withTimestamp: Message = withTimestamp(System.currentTimeMillis)
  private [eventsourced] def withTimestamp(timestamp: Long): Message = copy(timestamp = timestamp)
  private [eventsourced] def clearConfirmationSettings = copy(
    posConfirmationTarget = null,
    posConfirmationMessage = null,
    negConfirmationTarget = null,
    negConfirmationMessage = null)
}
