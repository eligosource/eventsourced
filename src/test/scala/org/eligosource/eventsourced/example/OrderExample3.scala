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
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object OrderExample3 extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journalDir = new java.io.File("target/example")
  val journal = LeveldbJournal(journalDir)

  // create destinations for output events
  val validator = system.actorOf(Props[CreditCardValidator])
  val destination = system.actorOf(Props[Destination])

  // create an event-sourcing component
  val orderComponent = Component(1, journal)

  // configure component
  orderComponent
    .addReliableOutputChannelToActor("validator", validator, Some(orderComponent))
    .addDefaultOutputChannelToActor("destination", destination)
    .setProcessor(outputChannels => system.actorOf(Props(new OrderProcessor(outputChannels))))

  // recover processor state from journaled events
  orderComponent.init()

  // submit an order
  orderComponent.inputProducer ? OrderSubmitted(Order("jelly beans", "1234")) onSuccess {
    case order: Order => println("received order %s" format order)
  }

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  class OrderProcessor(outputChannels: Map[String, ActorRef]) extends Actor {
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case msg: Message => msg.event match {
        case OrderSubmitted(order) => {
          val id = orders.size
          val upd = order.copy(id = id)
          orders = orders + (id -> upd)
          outputChannels("validator") ! msg.copy(event = CreditCardValidationRequested(upd))
        }
        case CreditCardValidated(orderId) => {
          orders.get(orderId).foreach { order =>
            val upd = order.copy(validated = true)
            orders = orders + (orderId -> upd)
            msg.sender.foreach(_ ! upd)
            outputChannels("destination") ! msg.copy(event = OrderAccepted(upd))
          }
        }
      }
    }
  }

  class CreditCardValidator extends Actor {
    def receive = {
      case msg: Message => msg.event match {
        case CreditCardValidationRequested(order) => {
          val s = sender
          Future {
            // do some credit card validation asynchronously
            // ...

            // and send back a successful validation result
            s ! msg.copy(event = CreditCardValidated(order.id))
          }
        }
      }
    }
  }

  class Destination extends Actor {
    def receive = {
      case msg: Message => { println("received event %s" format msg.event); sender ! Ack }
    }
  }
}
