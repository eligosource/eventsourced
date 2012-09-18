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

import akka.actor._

case class WriteInMsg(processorId: Int, message: Message, target: ActorRef, genSequenceNr: Boolean = true) {
  def withSequenceNr(snr: Long) = copy(message = message.copy(sequenceNr = snr), genSequenceNr = false)
}

case class WriteOutMsg(channelId: Int, message: Message, ackProcessorId: Int, ackSequenceNr: Long, target: ActorRef, genSequenceNr: Boolean = true) {
  def withSequenceNr(snr: Long) = copy(message = message.copy(sequenceNr = snr), genSequenceNr = false)
}

case class DeleteOutMsg(channelId: Int, msgSequenceNr: Long)
case class WriteAck(processorId: Int, channelId: Int, ackSequenceNr: Long)

case class ReplayInMsgs(processorId: Int, fromSequenceNr: Long, target: ActorRef)
case class ReplayOutMsgs(channelId: Int, fromSequenceNr: Long, target: ActorRef)

case class BatchReplayInMsgs(replays: Seq[ReplayInMsgs])
case class BatchDeliverOutMsgs(channels: Seq[ActorRef])

case class SetCommandListener(listener: Option[ActorRef])

case object GetCounter

