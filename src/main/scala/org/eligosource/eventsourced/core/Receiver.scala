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

trait Receiver extends ReceiverBehavior {
  private var _message: Option[Message] = None

  val autoAck = true

  /**
   * If true, concrete receivers will receive the whole event message
   * instead of the event only.
   */
  val forwardMessage = false

  /** Current event message option. */
  def messageOption: Option[Message] = _message

  /** Current event message. */
  def message: Message = messageOption.getOrElse(throw new IllegalStateException("no current event or command message"))

  /** Sender message id of current event message */
  def senderMessageId: Option[String] = message.senderMessageId

  /** Sequence number of current event message */
  def sequenceNr: Long = message.sequenceNr

  /** Initial message sender (initiator) option of current event message. */
  def initiatorOption: Option[ActorRef] = message.sender

  /** Initial message option (initiator) of current event message or deadLetters if the initiator is unknown. */
  def initiator: ActorRef = message.sender.getOrElse(context.system.deadLetters)

  /** Acknowledges receipt of current event message. */
  def ack() = sender ! Ack

  /** Negatively acknowledges receipt of current event message. */
  def nak(t: Throwable) = sender ! Status.Failure(t)

  abstract override def receive = {
    case msg: Message => {
      _message = Some(msg)
      super.receive(if (forwardMessage) msg else msg.event)
      if (autoAck) ack()
    }
    case msg => {
      _message = None
      super.receive(msg)
    }
  }
}

trait ForwardMessage extends Receiver {
  override val forwardMessage = true
}