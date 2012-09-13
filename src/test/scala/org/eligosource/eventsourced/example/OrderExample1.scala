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

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object OrderExample1 extends App {
  implicit val system = ActorSystem("example")

  // create a journal
  val journalDir = new java.io.File("target/example")
  val journal = LeveldbJournal(journalDir)

  // create a destination for output events
  val destination = system.actorOf(Props[Destination])

  // create an event-sourced processor
  val processor = system.actorOf(Props[Processor])

  // create and configure an event-sourcing component
  // with event processor and a named output channel
  val orderComponent = Component(1, journal)
    .addDefaultOutputChannelToActor("dest", destination)
    .setProcessor(processor)

  // recover processor state from journaled events
  orderComponent.init()

  // send some events
  orderComponent.inputProducer ! OrderSubmitted(Order("foo"))
  orderComponent.inputProducer ! OrderSubmitted(Order("bar"))

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // event-sourced processor
  class Processor extends Actor {
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case OrderSubmitted(order) => {
        val id = orders.size
        val upd = order.copy(id = id)
        orders = orders + (id -> upd)
        sender ! Publish("dest", OrderAccepted(upd))
      }
    }
  }

  // output event destination
  class Destination extends Actor {
    def receive = {
      case msg: Message => { println("received event %s" format msg.event); sender ! Ack }
    }
  }
}

