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
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.Timeout

/**
 * Event [[org.eligosource.eventsourced.core.Message]] `receiver` extension.
 *
 * @param receiver receiver actor reference.
 */
class TargetRef(receiver: ActorRef) {

  /**
   * Sends an event `message` to `receiver` where the `receiver` will receive
   * the sent `message` with a defined `sender` field that represents the returned
   * future.
   *
   * Actors that are modified with [[org.eligosource.eventsourced.core.Receiver]]
   * or any of its sub-traits can obtain the `sender` from the current event message
   * via the `Receiver.initiator` method. Sending a message to `initiator` (via
   * `initiator ! msg`) will complete the returned future.
   *
   * Completion of the future also works when a receiver further sends the received
   * `message` to other receivers, either directly or via channels.
   *
   * In contrast, when using Akka's `?` (ask) to send an event message, the future
   * will be completed with an `Ack` once the message has been written to the journal.
   *
   * @param message event message.
   * @param timeout reply timeout.
   */
  def ??(message: Message)(implicit timeout: Timeout, extension: EventsourcingExtension): Future[Any] =
    extension.producer.ask(Producer.Produce(message, receiver))
}

private [core] class Producer extends Actor {
  import Producer._

  def receive = {
    case Produce(msg: Message, target) => {
      target ! msg.copy(sender = Some(sender))
    }
  }
}

private [core] object Producer {
  case class Produce(msg: Message, target: ActorRef)
}

