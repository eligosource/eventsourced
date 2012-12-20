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
 * Stackable modification for actors to extract the `event` from a received
 * event [[org.eligosource.eventsourced.core.Message]] and calling the modified
 * actor's `receive` method with that `event`. Example:
 *
 * {{{
 *   val myReceiver = system.actorOf(Props(new MyReceiver with Receiver))
 *
 *   myReceiver ! Message("foo event")
 *
 *   class MyReceiver extends Actor { this: Receiver =>
 *     def receive = {
 *       case "foo event" => {
 *         val msg = message          // current message
 *         val snr = sequenceNr       // sequence number of message
 *
 *         assert(snr > 0L)
 *         // ...
 *       }
 *     }
 *   }
 * }}}
 *
 * Event messages received by concrete `Receiver`s are stored in a private field
 * and can be obtained via the `message` or `messageOption` method.
 *
 * The `Receiver` trait can also be used in combination with other stackable traits of the
 * library (such as [[org.eligosource.eventsourced.core.Confirm]] or
 * [[org.eligosource.eventsourced.core.Eventsourced]]), for example:
 *
 * {{{
 *   val myReceiver = system.actorOf(Props(new MyReceiver with Receiver with Confirm with Eventsourced { val id = ... } ))
 *
 *   class MyReceiver extends Actor { this: Receiver =>
 *     def receive = {
 *       // ...
 *     }
 *   }
 * }}}
 *
 */
trait Receiver extends Behavior {
  private var _message: Option[Message] = None

  /**
   * Current event message option. `None` if the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]].
   */
  def messageOption: Option[Message] = _message

  /**
   * Current event message.
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   *
   * @see `messageOption`
   */
  def message: Message = messageOption.getOrElse(throw new IllegalStateException("no current event or command message"))

  /**
   * Sequence number of current event message
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   */
  def sequenceNr: Long = message.sequenceNr

  /**
   * Positively or negatively confirms the receipt of the current event message.
   *
   * @param pos `true` for a positive receipt confirmation, `false` for a negative one.
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   */
  def confirm(pos: Boolean = true) = message.confirm(pos)

  abstract override def receive = {
    case msg: Message => {
      _message = Some(msg)
      super.receive(msg.event)
    }
    case msg => {
      _message = None
      super.receive(msg)
    }
  }
}
