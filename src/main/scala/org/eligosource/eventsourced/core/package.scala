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
package org.eligosource.eventsourced

import akka.actor.ActorRef

package object core {
  case class WriteAck(componentId: Int, channelId: Int, ackSequenceNr: Long)
  case class WriteMsg(componentId: Int, channelId: Int, message: Message, ackSequenceNr: Option[Long], target: ActorRef, genSequenceNr: Boolean = true) {
    def forSequenceNr(snr: Long) = copy(message = message.copy(sequenceNr = snr), genSequenceNr = false)
  }

  case class DeleteMsg(componentId: Int, channelId: Int, msgSequenceNr: Long)
  case class ReplayOutput(componentId: Int, channelId: Int, fromSequenceNr: Long, target: ActorRef)
  case class ReplayInput(componentId: Int, fromSequenceNr: Long, target: ActorRef)
  case class BatchReplayInput(replays: List[ReplayInput])

  case class SetCommandListener(listener: Option[ActorRef])

  case object GetCounter
  case object Ack
}