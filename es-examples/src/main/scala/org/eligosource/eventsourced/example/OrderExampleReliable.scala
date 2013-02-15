/*
 * Copyright 2012-2013 Eligotech BV.
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

import scala.concurrent.duration._

import akka.actor._
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Channel._
import org.eligosource.eventsourced.journal.journalio._
import org.eligosource.eventsourced.patterns.reliable.requestreply._

// ------------------------------------
// Domain object
// ------------------------------------

case class Order(id: Int = -1, details: String,
  creditCardNumber: String,
  creditCardValidation: Validation = Validation.Pending)

sealed trait Validation
object Validation {
  case object Pending extends Validation
  case object Success extends Validation
  case object Failure extends Validation
}

// ------------------------------------
// Domain events
// ------------------------------------

case class OrderSubmitted(order: Order)
case class OrderStored(order: Order)

case class OrderAccepted(order: Order, eventId: Long)
case class OrderRejected(order: Order, eventId: Long)

case class CreditCardValidationRequested(orderId: Int, creditCardNumber: String)
case class CreditCardValidated(orderId: Int)
case class CreditCardValidationFailed(orderId: Int)

// ------------------------------------
// Application commands/events
// ------------------------------------

case class SetCreditCardValidator(validator: ActorRef)
case class SetValidOrderDestination(destination: ActorRef)
case class SetInvalidOrderDestination(destination: ActorRef)

case class Recover(timeout: Timeout)
case object Recovered

// ------------------------------------
// Eventsourced order processor
// ------------------------------------

class OrderProcessor(val id: Int) extends Actor with ActorLogging { this: Receiver =>
  import Validation._

  val ext = EventsourcingExtension(context.system)
  val dl = context.system.deadLetters

  var validationRequestChannel: ActorRef = dl
  var validOrderChannel: ActorRef = dl
  var invalidOrderChannel: ActorRef = dl

  val validationRequestChannelId = id * 3 - 2
  val validOrderChannelId = id * 3 - 1
  val invalidOrderChannelId = id * 3

  var orders = Map.empty[Int, Order] // state

  def receive = {
    case OrderSubmitted(order) => {
      val id = orders.size
      val upd = order.copy(id = id)
      orders = orders + (id -> upd)
      sender ! OrderStored(upd)
      validationRequestChannel ! message.copy(CreditCardValidationRequested(id, order.creditCardNumber))
    }
    case CreditCardValidated(orderId) => {
      onValidationSuccess(orderId)
      confirm(true)
    }
    case CreditCardValidationFailed(orderId) => {
      onValidationFailure(orderId)
      confirm(true)
    }
    case DestinationNotResponding(channelId, failureCount, request) => {
      log.warning("Destination of channel {} does not respond (failure count = {}). Negatively confirm message receipt.", channelId, failureCount)
      confirm(false) // retry (or escalate)
    }
    case DestinationFailure(channelId, failureCount, CreditCardValidationRequested(orderId, _), throwable) => {
      if (failureCount > 2) {
        onValidationFailure(orderId)
        confirm(true)
      } else {
        log.warning("Destination of channel {} returned a failure (failure count = {}). Negatively confirm message receipt.", channelId, failureCount)
        confirm(false) // retry
      }
    }
    case DeliveryStopped(channelId) if (channelId == validationRequestChannelId) => {
      val delay = FiniteDuration(5, "seconds")
      log.warning("Channel {} stopped delivery. Reactivation in {}.", channelId, delay)
      context.system.scheduler.scheduleOnce(delay, validationRequestChannel, Deliver)(context.dispatcher)
    }
    case SetCreditCardValidator(validator) => {
      validationRequestChannel = ext.channelOf(ReliableRequestReplyChannelProps(validationRequestChannelId, validator)
        .withRedeliveryMax(3)
        .withRedeliveryDelay(0 seconds)
        .withRestartMax(1)
        .withRestartDelay(0 seconds)
        .withConfirmationTimeout(2 seconds)
        .withReplyTimeout(1 second))
    }
    case SetValidOrderDestination(destination) => {
      validOrderChannel = ext.channelOf(DefaultChannelProps(validOrderChannelId, destination))
    }
    case SetInvalidOrderDestination(destination) => {
      invalidOrderChannel = ext.channelOf(DefaultChannelProps(invalidOrderChannelId, destination))
    }
    case Recover(timeout) => {
      import context.dispatcher
      val initiator = sender
      val composite = for {
        _ <- ext.replay {
          case `id` => Some(0L)
          case _    => None
        } (timeout)
        _  <- ext.deliver(validationRequestChannelId)(timeout)
        _  <- ext.deliver(validOrderChannelId)(timeout)
        _  <- ext.deliver(invalidOrderChannelId)(timeout)
      } yield ()

      composite onSuccess {
        case _ => initiator ! Recovered
      }
    }
  }

  def onValidationSuccess(orderId: Int) {
    orders.get(orderId).filter(_.creditCardValidation == Pending).foreach { order =>
      val upd = order.copy(creditCardValidation = Success)
      orders = orders + (orderId -> upd)
      validOrderChannel ! message.copy(OrderAccepted(upd, sequenceNr))
    }
  }

  def onValidationFailure(orderId: Int) {
    orders.get(orderId).filter(_.creditCardValidation == Pending).foreach { order =>
      val upd = order.copy(creditCardValidation = Failure)
      orders = orders + (orderId -> upd)
      invalidOrderChannel ! message.copy(OrderRejected(upd, sequenceNr))
    }
  }

  override def preStart() {
    context.system.eventStream.subscribe(self, classOf[DeliveryStopped])
  }

  override def postStop() {
    context.system.eventStream.unsubscribe(self, classOf[DeliveryStopped])
  }
}

object OrderProcessor extends App {
  val config = ConfigFactory.load("order")
  val configCommon = config.getConfig("common")

  implicit val system = ActorSystem("example", config.getConfig("processor").withFallback(configCommon))
  implicit val timeout = Timeout(5 seconds)

  import system.dispatcher

  val log = Logging(system, this.getClass)
  val journal = Journal(JournalioJournalProps(new File("target/orders")))
  val extension = EventsourcingExtension(system, journal)

  val processor = extension.processorOf(ProcessorProps(1, id => new OrderProcessor(id) with Receiver with Eventsourced, Some("processor")))
  val destination = system.actorOf(Props(new OrderDestination with Receiver with Confirm), "destination")
  val validator = system.actorFor("akka://example@127.0.0.1:2852/user/validator")

  processor ! SetCreditCardValidator(validator)
  processor ! SetValidOrderDestination(destination)
  processor ! SetInvalidOrderDestination(destination)

  for {
    _ <- processor ? Recover(timeout)
    r1 <- processor ? Message(OrderSubmitted(Order(details = "jelly beans", creditCardNumber = "1234-5678-1234-5678")))
    r2 <- processor ? Message(OrderSubmitted(Order(details = "jelly beans", creditCardNumber = "1234-5678-1234-0000")))
  } {
    log.info("Reply 1: {}", r1)
    log.info("Reply 2: {}", r2)
  }
}

// ------------------------------------
//  Local receiver of orders after
//  credit card validation
// ------------------------------------

class OrderDestination extends Actor with ActorLogging { this: Receiver =>
  def receive = {
    case OrderAccepted(order, _) => log.info("Received accepted order: {}", order)
    case OrderRejected(order, _) => log.info("Received rejected order: {}", order)
  }
}

// ------------------------------------
//  Remote credit card validator
// ------------------------------------

class CreditCardValidator extends Actor {
  def receive = {
    case CreditCardValidationRequested(orderId, creditCardNumber) => {
      if (creditCardNumber.contains("0000")) {
        sender ! CreditCardValidationFailed(orderId)
      } else {
        sender ! CreditCardValidated(orderId)
      }
    }
  }
}

object CreditCardValidator extends App {
  val config = ConfigFactory.load("order")
  val configCommon = config.getConfig("common")
  val system = ActorSystem("example", config.getConfig("validator").withFallback(configCommon))
  system.actorOf(Props[CreditCardValidator], "validator")
}
