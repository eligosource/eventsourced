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
import akka.pattern.ask
import akka.util.duration._

/**
 * A multicast processor that forwards input messages to targets.
 */
class Multicast(targets: Seq[ActorRef]) extends Actor { this: Eventsourced with ForwardContext with ForwardMessage =>
  def receive = {
    case cmd: SetContext => {
      targets.foreach(_ ! cmd)
    }
    case msg: Message => {
      targets.foreach(_ ! msg)
    }
    case msg => {
      targets.foreach(_ ! msg)
    }
  }
}

/**
 * A decorating processor for actors that cannot add Eventsourced as stackable modification.
 */
class Decorator(target: ActorRef) extends Actor { this: Eventsourced =>
  import Decorator._

  val sequencer = context.actorOf(Props(new ResponseSequencer))
  var counter = 1L

  def receive = {
    case event => {
      val ctr = counter
      val emt = emitter

      target.ask(event)(5 seconds /* TODO: make configurable */)
      .onSuccess { case Emit(channel, event) => sequencer ! (ctr, (emt.forChannel(channel), event)) }
      .onFailure { case t                    => sequencer ! (ctr, (emt.forChannel("error"), t)) } // TODO: error handling
      counter = counter + 1
    }
  }
}

object Decorator {

  /**
   * Used by decorated actors to emit events to (named) channels.
   */
  case class Emit(channel: String, event: Any)
}


private [core] class ResponseSequencer extends Sequencer {
  def receiveSequenced = {
    case (emit: MessageEmitter, t: Throwable) => ()
    case (emit: MessageEmitter, event)        => emit.emitEvent(event)
  }
}

