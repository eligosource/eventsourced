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
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object OrderExample extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journalDir = new java.io.File("target/example")
  val journal = LeveldbJournal(journalDir)

  // create an event-sourcing extension
  implicit val extension = EventsourcingExtension(system, journal)

  // create destinations for output events
  val validator = system.actorOf(Props(new CreditCardValidator with Responder))
  val destination = system.actorOf(Props(new Destination with Receiver))

  // create event sourced processor
  val processor = extension.processorOf(ProcessorProps(1, new OrderProcessor with Emitter with Eventsourced))

  // create and register channels
  extension.channelOf(ReliableChannelProps(2, validator).withName("validator").withReplyDestination(processor))
  extension.channelOf(DefaultChannelProps(2, destination).withName("destination"))

  // recover state from (previously) journaled events
  extension.recover()

  // submit an order
  processor ?? Message(OrderSubmitted(Order("jelly beans", "1234"))) onSuccess {
    case order: Order => println("received order %s" format order)
  }

  // wait for output events to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()

  // ------------------------------------
  // domain object
  // ------------------------------------

  case class Order(id: Int, details: String, validated: Boolean, creditCardNumber: String)

  object Order {
    def apply(details: String): Order = apply(details, "")
    def apply(details: String, creditCardNumber: String): Order = new Order(-1, details, false, creditCardNumber)
  }

  // ------------------------------------
  // domain events
  // ------------------------------------

  case class OrderSubmitted(order: Order)
  case class OrderAccepted(order: Order)

  case class CreditCardValidationRequested(order: Order)
  case class CreditCardValidated(orderId: Int)

  // ------------------------------------
  // event-sourced order processor
  // ------------------------------------

  class OrderProcessor extends Actor { this: Emitter =>
    var orders = Map.empty[Int, Order] // processor state

    def receive = {
      case OrderSubmitted(order) => {
        val id = orders.size
        val upd = order.copy(id = id)
        orders = orders + (id -> upd)
        emitter("validator").emitEvent(CreditCardValidationRequested(upd))
      }
      case CreditCardValidated(orderId) => {
        orders.get(orderId).foreach { order =>
          val upd = order.copy(validated = true)
          orders = orders + (orderId -> upd)
          initiator ! upd
          emitter("destination").emitEvent(OrderAccepted(upd))
        }
      }
    }
  }

  // ------------------------------------
  //  channel destinations
  // ------------------------------------

  trait CreditCardValidator extends Actor { this: Responder =>
    def receive = {
      case CreditCardValidationRequested(order) => {
        val r = responder
        Future {
          // do some credit card validation asynchronously
          // ...

          // and send back a successful validation result
          r.sendEvent(CreditCardValidated(order.id))
        }
      }
    }
  }

  class Destination extends Actor {
    def receive = {
      case event => println("received event %s" format event)
    }
  }
}
