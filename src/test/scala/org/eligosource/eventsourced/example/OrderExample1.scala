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
package org.eligosource.eventsourced.example

import java.io.File

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object OrderExample1 extends App {
  implicit val system = ActorSystem("example")

  // create a journal
  val journal = LeveldbJournal(new File("target/example"))

  // create an event-sourcing extension
  val extension = EventsourcingExtension(system, journal)

  // create a destination for output events
  val destination = system.actorOf(Props(new Destination with Receiver))

  // create an event-sourced processor
  val processor = extension.processorOf(ProcessorProps(1, new Processor with Emitter with Eventsourced))

  // create and register a channel
  extension.channelOf(DefaultChannelProps(1, destination).withName("dest"))

  // recover state from (previously) journaled events
  extension.recover()

  // send some event messages
  processor ! Message(OrderSubmitted(Order("foo")))
  processor ! Message(OrderSubmitted(Order("bar")))

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // event-sourced processor
  class Processor extends Actor { this: Emitter =>
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case OrderSubmitted(order) => {
        val id = orders.size
        val upd = order.copy(id = id)
        orders = orders + (id -> upd)
        emitter("dest").emitEvent(OrderAccepted(upd))
      }
    }
  }

  // output event destination
  class Destination extends Actor {
    def receive = {
      case event => println("received event %s" format event)
    }
  }
}

