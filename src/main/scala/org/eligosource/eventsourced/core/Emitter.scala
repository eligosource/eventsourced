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
 * Stackable modification for actors that need access to the channels of
 * a [[org.eligosource.eventsourced.core.Context]]. Actors modified with
 * `Emitter` can ''emit'' event messages to these channels. A context is
 * injected into an `Emitter` via a `SetContext` message.
 *
 * `Emitter`s are all actors that are modified with the [[org.eligosource.eventsourced.core.Eventsourced]]
 * trait. An example of actors that are directly modified with the `Emitter` trait are the `targets` of
 * [[org.eligosource.eventsourced.core.Multicast]] processors.
 *
 * @see [[org.eligosource.eventsourced.core.Eventsourced]] for a usage example.
 */
trait Emitter extends Receiver {
  private var _context: Context = _

  /**
   * Returns the journal of the injected [[org.eligosource.eventsourced.core.Context]]
   */
  def journal: ActorRef = _context.journal

  /**
   * Returns the channel map of the injected [[org.eligosource.eventsourced.core.Context]]
   */
  def channels: Map[String, ActorRef] = _context.channels

  /** Overrides to `false` */
  override val autoAck = false

  /**
   * If `true`, concrete `Emitter`s will be forwarded the `SetContext` message which is
   * otherwise retained by this trait. Default is `false` and can be set to `true` by
   * mixing in [[org.eligosource.eventsourced.core.ForwardContext]].
   */
  val forwardContext = false

  /**
   * Returns a message emitter factory that captures the current event `message` and the
   * `channels` map of the injected context. Applications can run the returned emitter factory
   * from within any thread.
   */
  def emitter = {
    new MessageEmitterFactory(channels, message)(context.system)
  }

  /**
   * Returns a message emitter that captures the current event `message` and a channel
   * mapped with `channelName` in the `channels` map of the injected context. If the mapping
   * doesn't exist, the channel will be `context.system.deadLetters`. Applications can run
   * the returned emitter from within any thread for sending output messages (which will be
   * derived from the captured event `message`).
   */
  def emitter(channelName: String) = {
    new MessageEmitter(channels.getOrElse(channelName, context.system.deadLetters), message)
  }

  abstract override def receive = {
    case cmd: SetContext => {
      _context = cmd.context
      if (forwardContext) super.receive(cmd)
    }
    case msg: Message => {
      super.receive(msg)
    }
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

/**
 * Stackable modification for [[org.eligosource.eventsourced.core.Emitter]] to set
 * `Emitter.forwardContext` to `true`. Usually not used by applications.
 */
trait ForwardContext extends Emitter {
  override val forwardContext = true
}
