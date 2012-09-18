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

trait Receiver extends Actor {
  private var _message: Option[Message] = None

  val autoAck = true

  def messageOption = _message
  def message = messageOption.getOrElse(throw new IllegalStateException("no current event or command message"))

  def senderMessageId = message.senderMessageId
  def sequenceNr = message.sequenceNr

  /** Application-defined initial message sender. */
  def initiator = message.sender.getOrElse(context.system.deadLetters)

  /** Acknowledges receipt of message to sending channel. */
  def ack() = sender ! Ack

  /** Negatively acknowledges receipt of message to sending channel. */
  def nak(t: Throwable) = sender ! Status.Failure(t)

  abstract override def receive = {
    case msg: Message => {
      _message = Some(msg)
      super.receive(msg.event)
      if (autoAck) sender ! Ack
    }
    case msg => {
      _message = None
      super.receive(msg)
    }
  }
}

trait Responder extends Receiver {
  override val autoAck = false

  /**
   * Returns a responder that can be used for asynchronously sending
   * responses to sending channels.
   */
  def respond: Respond = {
    new Respond(sender, message)
  }

  abstract override def receive = {
    case msg => super.receive(msg)
  }
}

/**
 * Responder that can be used for asynchronously sending
 * responses to sending channels.
 */
class Respond(val chn: ActorRef, val msg: Message) {
  def withMessage(f: Message => Message) {
    chn ! f(msg)
  }

  def withEvent(event: Any) = {
    chn ! msg.copy(event = event)
  }

  def withFailure(t: Throwable) = {
    chn ! Status.Failure(t)
  }
}
