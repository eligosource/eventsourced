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
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object OrderExample2 extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journal = LeveldbJournal(new File("target/example"))

  // create an event-sourcing context
  implicit val context = Context(journal)
    // create and add a destination for output events
    .addChannel("dest", new Destination with Receiver)
    // create and add an event-sourced processor
    .addProcessor(1, new Processor with Eventsourced)
    // recover state from (previously) journaled events
    .init()

  // get processor with id == 1 from context
  val p = context.processors(1)

  // send some event messages
  p ! Message(OrderSubmitted(Order("foo")))
  p ! Message(OrderSubmitted(Order("bar")))

  // and expect an application-level reply
  p ?? Message(OrderSubmitted(Order("baz"))) onSuccess {
    case order: Order => println("received order %s" format order)
  }

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // event-sourced processor
  class Processor extends Actor { this: Eventsourced =>
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case OrderSubmitted(order) => {
        val id = orders.size
        val upd = order.copy(id = id)
        orders = orders + (id -> upd)
        initiator ! upd
        emitTo("dest").event(OrderAccepted(upd))
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
