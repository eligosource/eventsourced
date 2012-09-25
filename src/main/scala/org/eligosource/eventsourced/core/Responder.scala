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

trait Responder extends Receiver {
  override val autoAck = false

  /**
   * Returns a responder that can be used for asynchronously sending
   * responses to sending channels.
   */
  def responder: ResponseMessageSender = {
    new ResponseMessageSender(sender, message)
  }

  abstract override def receive = {
    case msg => super.receive(msg)
  }
}

/**
 * Responder that can be used for asynchronously sending
 * responses to sending channels.
 */
class ResponseMessageSender(val chn: ActorRef, val msg: Message) {
  def send(f: Message => Message) {
    chn ! f(msg)
  }

  def sendEvent(event: Any) = {
    chn ! msg.copy(event = event)
  }

  def sendFailure(t: Throwable) = {
    chn ! Status.Failure(t)
  }
}
