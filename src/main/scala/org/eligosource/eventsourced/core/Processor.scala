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
 * Wraps a processor.
 */
class Processor(outputChannels: Map[String, ActorRef], processor: ActorRef) extends Actor {
  var counter = 1L
  val sequencer = context.actorOf(Props(new ProcessorOutputSequencer(outputChannels)))

  def receive = {
    case msg: Message => {
      val ctr = counter
      processor.ask(msg.event)(5 seconds /* TODO: make configurable */) onSuccess {
        case (channel: String, event) => sequencer ! (ctr, (outputChannels.get(channel), msg.copy(event = event)))
      } onFailure {
        case t => { sequencer ! (ctr, None, ()) } // TODO: error handling
      }
      counter = counter + 1
    }
  }
}

/**
 * Resequences asynchronous responses from wrapped processor.
 */
class ProcessorOutputSequencer(outputChannels: Map[String, ActorRef]) extends Sequencer {
  def receiveSequenced = {
    case (channel: Option[ActorRef], message: Message) => channel.foreach(_ ! message)
    case (channel: Option[ActorRef], t: Throwable)     => ()
  }
}