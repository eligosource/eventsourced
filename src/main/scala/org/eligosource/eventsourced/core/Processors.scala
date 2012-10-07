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
 * An [[org.eligosource.eventsourced.core.Eventsourced]] processor that forwards
 * received event [[org.eligosource.eventsourced.core.Message]]s to `targets`.
 *
 * Using `Multicast` is useful in situtations where mutliple processors should
 * receive the same event messages but an application doesn't want them to journal
 * these messages redundantly.
 */
class Multicast(targets: Seq[ActorRef]) extends Actor { this: Eventsourced =>
  def receive = {
    case msg => targets.foreach(_ ! msg)
  }
}

/**
 * An [[org.eligosource.eventsourced.core.Eventsourced]] decorator for actors that
 * cannot be modified with [[org.eligosource.eventsourced.core.Eventsourced]] (such
 * as Akka FSMs since they implement `Actor.receive` as `final`).
 *
 * A `Decorator` extracts events from received event [[org.eligosource.eventsourced.core.Message]]s
 * and sends them to the decorated actor. The decorated actor must reply with `Emit(channelName, event)`
 * messages to instruct the decorator to emit a [[org.eligosource.eventsourced.core.Message]] containing
 * `event` to a named channel.
 *
 * Experimental.
 */
class Decorator(target: ActorRef) extends Actor { this: Emitter with Eventsourced =>
  import Decorator._

  val sequencer = context.actorOf(Props(new ResponseSequencer with Sequencer))
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
   * Used by decorated actors to emit an `event` to a named channel.
   */
  case class Emit(channelName: String, event: Any)
}


private [core] class ResponseSequencer extends Actor { this: Sequencer =>
  def receive = {
    case (emit: MessageEmitter, t: Throwable) => ()
    case (emit: MessageEmitter, event)        => emit.emitEvent(event)
  }
}
