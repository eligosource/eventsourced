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

import scala.concurrent.Await._
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.leveldb._

class Sender(receiverPath: String) extends Actor {
  val id = 1
  val ext = EventsourcingExtension(context.system)
  val proxy = context.actorOf(Props(new DestinationProxy(receiverPath)))
  val channel = ext.channelOf(ReliableChannelProps(1, proxy)
    .withRedeliveryMax(1000)
    .withRedeliveryDelay(1 second)
    .withConfirmationTimeout(2 seconds))

  def receive = {
    case msg: Message => {
      sender ! s"accepted ${msg.event}"
      channel forward msg
    }
  }
}

class DestinationProxy(destinationPath: String) extends Actor {
  val destinationSelection: ActorSelection = context.actorSelection(destinationPath)

  def receive = {
    case msg => destinationSelection tell (msg, sender) // forward
  }
}

    class DestinationEndpoint(destination: ActorRef) extends Actor {
      def receive = {
        case msg => destination forward msg
      }
    }

    class Destination extends Actor {
      val id = 2

      def receive = {
        case msg: Message => {
          println(s"received ${msg.event}")
          msg.confirm()
        }
      }
    }

object Sender extends App {
  val config = ConfigFactory.load("reliable")
  val common = config.getConfig("common")

  implicit val system = ActorSystem("example", config.getConfig("sender").withFallback(common))
  implicit val timeout = Timeout(5 seconds)

  val journal = LeveldbJournalProps(new File("target/example-sender"), native = false).createJournal
  val extension = EventsourcingExtension(system, journal)

  val sender = extension.processorOf(Props(new Sender("akka.tcp://example@127.0.0.1:2852/user/destination") with Eventsourced))

  extension.recover()

  while (true) {
    print("enter a message: ")
    Console.readLine() match {
      case "exit" => System.exit(0)
      case msg    => println(result(sender ? Message(msg), timeout.duration))
    }
  }
}

object Destination extends App {
  val config = ConfigFactory.load("reliable")
  val common = config.getConfig("common")

  implicit val system = ActorSystem("example", config.getConfig("destination").withFallback(common))

  val journal = LeveldbJournalProps(new File("target/example-destination"), native = false).createJournal
  val extension = EventsourcingExtension(system, journal)

  val destination = extension.processorOf(Props(new Destination with Eventsourced))

  // wait for destination recovery to complete
  extension.recover()

  // make destination remotely accessible after recovery
  system.actorOf(Props(new DestinationEndpoint(destination)), "destination")
}
