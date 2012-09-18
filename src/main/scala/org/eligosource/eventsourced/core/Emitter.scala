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

trait Emitter extends Receiver {
  private var _context: Context = _

  def journal = _context.journal
  def channels = _context.channels

  override val autoAck = false
  val forwardSetContext = false

  /**
   * Returns an emitter that can be used for asynchronously emitting events
   * to channels.
   */
  def emit = {
    new Emit(channels, message)(context.system)
  }

  /**
   * Returns an emitter that can be used for asynchronously emitting events
   * to an channel.
   */
  def emitTo(channelName: String) = {
    new EmitTo(channels.getOrElse(channelName, context.system.deadLetters), message)
  }

  abstract override def receive = {
    case cmd: SetContext => {
      _context = cmd.context
      if (forwardSetContext) super.receive(cmd)
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
 * Emitter that can be used for asynchronously emitting events
 * to channels.
 */
class Emit(val chns: Map[String, ActorRef], val msg: Message)(implicit system: ActorSystem) {
  def to(channelName: String) = new EmitTo(chns.getOrElse(channelName, system.deadLetters), msg)
}

/**
 * Emitter that can be used for asynchronously emitting events
 * to an channel.
 */
class EmitTo(val chn: ActorRef, val msg: Message) {
  def message(f: Message => Message) = {
    chn ! f(msg)
  }

  def event(event: Any) = {
    chn ! msg.copy(event = event)
  }
}

/**
 * Stackable modification that enables forwarding of SetContext commands
 * to concrete emitters.
 */
trait ForwardSetContext extends Emitter {
  override val forwardSetContext = true
}
