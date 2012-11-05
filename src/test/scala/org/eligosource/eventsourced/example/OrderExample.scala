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

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object OrderExample extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  import system.dispatcher

  val journalDir = new java.io.File("target/example-1")
  val journal = LeveldbJournal(journalDir)

  val extension = EventsourcingExtension(system, journal)

  val processor = extension.processorOf(Props(new OrderProcessor with Emitter with Confirm with Eventsourced { val id = 1 }))
  val validator = system.actorOf(Props(new CreditCardValidator(processor) with Receiver))
  val destination = system.actorOf(Props(new Destination with Receiver with Confirm))

  extension.channelOf(ReliableChannelProps(1, validator).withName("validation requests"))
  extension.channelOf(DefaultChannelProps(2, destination).withName("accepted orders"))
  extension.recover()

  processor ? Message(OrderSubmitted(Order(details = "jelly beans", creditCardNumber = "1234-5678-1234-5678"))) onSuccess {
    case order: Order => println("received response %s" format order)
  }

  Thread.sleep(1000) // wait for output events to arrive (graceful shutdown coming soon)
  system.shutdown()

  // ------------------------------------
  // domain object
  // ------------------------------------

  case class Order(id: Int = -1, details: String, validated: Boolean = false, creditCardNumber: String)

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
        emitter("validation requests") forwardEvent CreditCardValidationRequested(upd)
      }
      case CreditCardValidated(orderId) => {
        orders.get(orderId).foreach { order =>
          val upd = order.copy(validated = true)
          orders = orders + (orderId -> upd)
          sender ! upd
          emitter("accepted orders") sendEvent OrderAccepted(upd)
        }
      }
    }
  }

  // ------------------------------------
  //  channel destinations
  // ------------------------------------

  class CreditCardValidator(orderProcessor: ActorRef) extends Actor { this: Receiver =>
    def receive = {
      case CreditCardValidationRequested(order) => {
        val sdr = sender  // initial sender
        val msg = message // current message
        Future {
          // do some credit card validation asynchronously
          // ...

          // and send back a successful validation result (preserving the initial sender)
          orderProcessor tell (msg.copy(event = CreditCardValidated(order.id)), sdr)

          // please note that this receiver does NOT confirm message receipt. The confirmation
          // is done by the order processor when it receives the CreditCardValidated event.
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
