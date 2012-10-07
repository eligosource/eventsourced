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
 * [[org.eligosource.eventsourced.core.Channel]]s and to ''emit'' event messages to
 * these channels. Registered channels are those that have been created with the
 * `EventsourcingExtension.channelOf` method.
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
 *         emitter("channelA").emitEvent("bar event")
 *         emitter("channelB").emitEvent("baz event")
 *         // ...
 *       }
 *     }
 *   }
 * }}}
 *
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
   * Overrides to `false`.
   */
  override val autoAck = false

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
 * Message emitter factory for creating message emitters for a certain channel.
 *
 * @param channels channel map.
 * @param message event message.
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
 * Message emitter for emitting event messages to a channel.
 *
 * @param channel event message channel.
 * @param message event message.
 */
class MessageEmitter(val channel: ActorRef, val message: Message) {
  /**
   * Updates `message` with function `f` and emits the updated
   * message to `channel`.
   */
  def emit(f: Message => Message) = {
    channel ! f(message)
  }

  /**
   * Updates `message` with `event` and emits the updated message to `channel`.
   */
  def emitEvent(event: Any) = {
    channel ! message.copy(event = event)
  }
}
