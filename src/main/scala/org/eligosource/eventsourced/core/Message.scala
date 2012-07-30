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
 * An event messages used by an event-sourced Component to communicate with it's
 * environment.
 *
 * @param event the event.
 * @param sender an optional, application-defined sender reference that can be used
 *        by processors to send responses.
 * @param senderMessageId an optional, application-defined message id in order to
 *        allow receivers to detect duplicates (which may occur during recovery or
 *        fail-over).
 * @param sequenceNr a message sequence number assigned by a component's input channel.
 * @param acks list of output channel ids that have acknowledged a message-send. This
 *        list is only non-empty during a replay.
 * @param ack whether or not to acknowledge this message.
 * @param replicated true if this is a replicated message.
 */
case class Message(
  event: Any,
  sender: Option[ActorRef] = None,
  senderMessageId: Option[String] = None,
  sequenceNr: Long = 0L,
  acks: List[Int] = Nil,
  ack: Boolean = true,
  replicated: Boolean = false
)
