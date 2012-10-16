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
 * Stackable modification for actors to provide ''convenient'' access to registered
 * [[org.eligosource.eventsourced.core.Channel]]s and to ''emit'' event
 * [[org.eligosource.eventsourced.core.Message]]s to these channels. Registered channels
 * are those that have been created with the `EventsourcingExtension.channelOf` method.
 *
 * {{{
 *   val myEmitter = system.actorOf(Props(new MyEmitter with Emitter))
 *
 *   myEmitter ! Message("foo event")
 *
 *   class MyEmitter extends Actor { this: Emitter =>
 *     def receive = {
 *       case "foo event" => {
 *         // emit event messages to named channels (where emitted
 *         // event messages are derived from the current message)
 *
 *         // emit event to channelA setting this actor as sender
 *         emitter("channelA") sendEvent "bar event"
 *
 *         // emit event to channelB preserving the original sender
 *         emitter("channelB") forwardEvent "baz event"
 *         // ...
 *       }
 *     }
 *   }
 * }}}
 *
 * Applications may also use [[org.eligosource.eventsourced.core.Channel]] actor references
 * directly to emit event messages to destinations (using the `!`, `?` or `forward` methods
 * on `ActorRef`).
 *
 * The `Emitter` trait can also be used in combination with other stackable traits of the
 * library (such as [[org.eligosource.eventsourced.core.Confirm]] or
 * [[org.eligosource.eventsourced.core.Eventsourced]]), for example:
 *
 * {{{
 *   val myEmitter = system.actorOf(Props(new MyEmitter with Emitter with Confirm with Eventsourced { val id = ... } ))
 *
 *   class MyEmitter extends Actor { this: Emitter =>
 *     def receive = {
 *       // ...
 *     }
 *   }
 * }}}
 *
 * @see [[org.eligosource.eventsourced.core.MessageEmitter]]
 *      [[org.eligosource.eventsourced.core.MessageEmitterFactory]]
 */
trait Emitter extends Receiver {
  private val extension = EventsourcingExtension(context.system)

  /**
   * Returns a map of registered [[org.eligosource.eventsourced.core.Channel]]s.
   */
  def channels: Map[String, ActorRef] = extension.channels

  /**
   * Returns a message emitter factory that captures the current event `message` and the
   * map of registered `channels`. Applications can run the returned emitter factory
   * from within any thread.
   */
  def emitter = {
    new MessageEmitterFactory(channels, message)(context.system)
  }

  /**
   * Returns a message emitter that captures the current event `message` and a channel
   * that has been registered under `channelName`. If no channel exists for `channelName`
   * it will be `context.system.deadLetters`. Applications can run the returned emitter
   * from within any thread for sending output messages (which will be derived from the
   * captured event `message`).
   */
  def emitter(channelName: String) = {
    new MessageEmitter(channels.getOrElse(channelName, context.system.deadLetters), message)
  }

  abstract override def receive = {
    case msg => {
      super.receive(msg)
    }
  }
}

/**
 * Factory for creating [[org.eligosource.eventsourced.core.MessageEmitter]]s
 * for a certain [[org.eligosource.eventsourced.core.Channel]].
 *
 * @param channels channel map.
 * @param message event message.
 *
 * @see [[org.eligosource.eventsourced.core.Emitter]]
 */
class MessageEmitterFactory(val channels: Map[String, ActorRef], val message: Message)(implicit system: ActorSystem) {
  /**
   * Returns a message emitter containing `message` and a channel mapped
   * with `channelName` in `channels`. If the mapping doesn't exist the
   * channel will be `system.deadLetters`.
   *
   * @param channelName mapping key in `channels`
   */
  def forChannel(channelName: String) = new MessageEmitter(channels.getOrElse(channelName, system.deadLetters), message)
}

/**
 * Message emitter for emitting event [[org.eligosource.eventsourced.core.Message]]s
 * to a [[org.eligosource.eventsourced.core.Channel]].
 *
 * Emitter usage is optional. Applications may also use
 * [[org.eligosource.eventsourced.core.Channel]] actor references
 * directly to emit event messages to destinations (using the `!`,
 * `?` or `forward` methods on `ActorRef`).
 *
 * @param channel event message channel.
 * @param message event message.
 *
 * @see [[org.eligosource.eventsourced.core.Emitter]]
 */
class MessageEmitter(val channel: ActorRef, val message: Message) {
  /**
   * Updates `message` with function `f` and emits the updated
   * message to `channel` using `sender` as sender reference.
   * If invoked from within an actor then the actor reference
   * is implicitly passed on as the implicit `sender` argument.
   *
   * @param f message update function.
   * @param sender sender reference.
   */
  def send(f: Message => Message)(implicit sender: ActorRef = null) = {
    channel ! f(message)
  }

  /**
   * Updates `message` with function `f` and emits the updated
   * message to `channel` passing the original sender actor as
   * the sender.
   *
   * @param f message update function.
   * @param context actor context.
   */
  def forward(f: Message => Message)(implicit context: ActorContext) = {
    channel forward f(message)
  }

  /**
   * Updates `message` with `event` and emits the updated message to
   * `channel` using `sender` as sender reference. If invoked from
   * within an actor then the actor reference is implicitly passed
   * on as the implicit `sender` argument.
   *
   * @param event event to be emitted.
   * @param sender sender reference.
   */
  def sendEvent(event: Any)(implicit sender: ActorRef = null) = {
    channel ! message.copy(event = event)
  }

  /**
   * Updates `message` with `event` and emits the updated message to
   * `channel` passing the original sender actor as the sender.
   *
   * @param event event to be emitted.
   * @param context actor context.
   */
  def forwardEvent(event: Any)(implicit context: ActorContext) = {
    channel forward message.copy(event = event)
  }
}
