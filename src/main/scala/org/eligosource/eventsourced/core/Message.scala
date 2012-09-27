/*
 * Copyright 2012 Eligotech BV.
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
 * A message to communicate application events. Application events are not interpreted
 * by the library and can have any type.
 *
 * Messages sent to an [[org.eligosource.eventsourced.core.Eventsourced]] processor
 * are called ''input'' messages. Processors process input messages by deriving zero
 * or more ''output'' messages from it that the processor ''emits'' to a channel. When
 * a channel successfully delivered (or stored, in case of a reliable channel) the
 * emitted output message(s), an acknowledgement (with a reference to the input message)
 * is written to the journal. During a replay (recovery), output messages that are derived
 * from already acknowledged input messages are ignored by channels, avoiding unnecessary
 * re-deliveries to channel destinations.
 *
 * Since the [[https://github.com/eligosource/eventsourced eventsourced library]] doesn't
 * make any assumptions about the structure and semantics of `event`, applications may also
 * choose to send ''commands'' with [[org.eligosource.eventsourced.core.Message]]s. In other
 * words, the library can be used for both, event-sourcing and command-sourcing.
 *
 * @param event Application event (or command).
 * @param sender Optional, application-defined sender reference that can be used
 *        by event processors to send responses to initial event message senders.
 * @param senderMessageId Optional, sender-defined message id that allows receivers
 *        to detect duplicates (which may occur during recovery or fail-over).
 * @param sequenceNr Sequence number which is generated when messages are written
 *        to a journal. Can also be used for detecting duplicates, in special cases.
 * @param processorId id of the event processor that stored (and emitted) this message
 *        The processor id is given by `Eventsourced.id`.
 * @param acks List of channel ids that have acknowledged message delivery (or storage).
 *        This sequence is only non-empty during recovery (i.e. message replay). Usually
 *        not used by applications.
 * @param ack Whether or not a channel should write an acknowledgement to the journal.
 *        Used by event processors to emit a series of messages (derived from a single
 *        input message) where only for the last emitted message an acknowledgement
 *        should be written.
 */
case class Message(
  event: Any,
  sender: Option[ActorRef] = None,
  senderMessageId: Option[String] = None,
  sequenceNr: Long = 0L,
  processorId: Int = 0,
  acks: Seq[Int] = Nil,
  ack: Boolean = true
)
