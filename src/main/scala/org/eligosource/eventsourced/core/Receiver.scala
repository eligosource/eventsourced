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
 * Stackable modification for actors that receive event [[org.eligosource.eventsourced.core.Message]]s.
 * Example:
 *
 * {{{
 *   val myReceiver = system.actorOf(Props(new MyReceiver with Receiver))
 *
 *   myReceiver ! Message("foo event")
 *
 *   class MyReceiver extends Actor { this: Receiver =>
 *     def receive = {
 *       case "foo event" => {
 *         assert(message.sequenceNr > 0L)
 *         // ...
 *       }
 *     }
 *   }
 * }}}
 *
 * Event messages received by concrete [[org.eligosource.eventsourced.core.Receiver]]s are stored
 * in a private field and can be obtained via the `message` or `messageOption` method. The `receive`
 * method of the concrete receiver is called to the message `event` only. The receipt of an event
 * message is automatically acknowledged by [[org.eligosource.eventsourced.core.Receiver]].
 *
 * Often channel destinations use this trait for receiving event messages from their channel and
 * acknowledging their receipt. The receipt is not acknowledged if the concrete receiver throws
 * an exception.
 */
trait Receiver extends ReceiverBehavior {
  private var _message: Option[Message] = None

  /**
   * If `true`, auto-acknowledges the receipt of an event [[org.eligosource.eventsourced.core.Message]].
   * Default is `true` and can be overridden.
   */
  protected [core] val autoAck = true

  /**
   * If `true`, concrete receivers will receive the whole event [[org.eligosource.eventsourced.core.Message]]
   * instead of the event only. Default is `false` and can be set to `true`
   * by mixing in [[org.eligosource.eventsourced.core.ForwardMessage]].
   */
  val forwardMessage = false

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
   * Sender message id of current event message.
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   */
  def senderMessageId: Option[String] = message.senderMessageId

  /**
   * Sequence number of current event message
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   */
  def sequenceNr: Long = message.sequenceNr

  /**
   * Initial event message sender (initiator) option of current event message.
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   */
  def initiatorOption: Option[ActorRef] = message.sender

  /**
   * Initial message option (initiator) of current event message or deadLetters
   * if the initiator is unknown.
   *
   * @throws IllegalStateException if the the last message received by this receiver
   * is not of type [[org.eligosource.eventsourced.core.Message]]
   */
  def initiator: ActorRef = message.sender.getOrElse(context.system.deadLetters)

  /**
   * Acknowledges the receipt of current event message by replying with `Ack`.
   * The reply goes to `Actor.sender` (a channel, for example) not the initiator.
   */
  def ack() = sender ! Ack

  /**
   * Negatively acknowledges the receipt of current event message by replying with
   * `Status.Failure`. The reply goes to `Actor.sender` (a channel, for example)
   * not the initiator.
   */
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

/**
 * Stackable modification for [[org.eligosource.eventsourced.core.Receiver]] to set
 * `Receiver.forwardMessage` to `true`. Usually not used by applications.
 */
trait ForwardMessage extends Receiver {
  override val forwardMessage = true
}