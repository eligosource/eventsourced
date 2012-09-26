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

/**
 * Stackable modification for actors that receive event [[org.eligosource.eventsourced.core.Message]]s
 * and generate response [[org.eligosource.eventsourced.core.Message]]s. Concrete
 * responders are usually destinations of channels with a reply destination. Example:
 *
 * {{{
 *   val myResponder = system.actorOf(Props(new MyResponder with Receiver))
 *
 *   myResponder ! Message("foo event")
 *
 *   class MyResponder extends Actor { this: Responder =>
 *     def receive = {
 *       case "foo event" => {
 *         assert(message.sequenceNr > 0L)
 *         // ...
 *
 *         // create a responder object for the current event message
 *         val rsp = responder
 *
 *         // send a response message by updating the current message
 *         rsp.sendEvent("bar event")
 *       }
 *     }
 *   }
 * }}}
 *
 * Applications need not send the response during execution of `receive`. They can
 * use the created responder object (`rsp` in the above example) to send a response
 * any time later (e.g. in another thread). The object created by `responder` captures
 * the current event message and sender.
 *
 * When using [[org.eligosource.eventsourced.core.Responder]] for destinations of
 * channels with no reply destination, concrete responders  must explicitly acknowledge
 * a message receipt by calling `ack()` (or `nak()` for replying with `Status.Failure`).
 * This way, applications can implement application-level acknowledgements, in contrast
 * to auto-acknowledgements as done with [[org.eligosource.eventsourced.core.Receiver]].
 */
trait Responder extends Receiver {

  /** Overrides to `false` */
  override val autoAck = false

  /**
   * Returns a response message sender (responder) that captures the current
   * event message and sender.
   */
  def responder: ResponseMessageSender = {
    new ResponseMessageSender(sender, message)
  }

  abstract override def receive = {
    case msg => super.receive(msg)
  }
}

/**
 * A response message sender (responder).
 *
 * @param sender sender reference.
 * @param message event message.
 */
class ResponseMessageSender(val sender: ActorRef, val message: Message) {
  /**
   * Updates `message` with function `f` and sends the updated
   * message to `sender`.
   */
  def send(f: Message => Message) {
    sender ! f(message)
  }

  /**
   * Updates `message` with `event` and sends the updated message to `sender`.
   */
  def sendEvent(event: Any) = {
    sender ! message.copy(event = event)
  }

  /**
   * Sends a `Status.Failure` with given `t` to `sender`.
   */
  def sendFailure(t: Throwable) = {
    sender ! Status.Failure(t)
  }
}
