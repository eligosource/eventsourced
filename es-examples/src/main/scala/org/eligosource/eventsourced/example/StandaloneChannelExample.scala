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
import scala.util._

import akka.actor._
import akka.pattern.ask
import akka.util._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps

object StandaloneChannelExample extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(50 seconds)

  import system.dispatcher

  val journal: ActorRef = LeveldbJournalProps(new File("target/standalone"), native = false).createJournal
  val extension = EventsourcingExtension(system, journal)

  class Destination extends Actor { this: Receiver =>
    var ctr = 0

    def receive = {
      case event => {
        ctr += 1
        println(s"event = ${event}, counter = ${ctr}")
        if (ctr > 2) {
          sender ! s"received ${event}"
          confirm()
          ctr = 0
        }
      }
    }
  }

  val policy = RedeliveryPolicy().copy(confirmationTimeout = 1 second, restartDelay = 1 second, redeliveryDelay = 0 seconds)
  val destination: ActorRef = system.actorOf(Props(new Destination with Receiver))
  val channel1 = extension.channelOf(ReliableChannelProps(1, destination, policy))

  extension.recover()

  channel1 ? "a" onComplete {
    case Success(r) => println(s"reply = ${r}")
    case Failure(e) => println(s"error = ${e.getMessage}")
  }

  channel1 ? Message("b") onComplete {
    case Success(r) => println(s"reply = ${r}")
    case Failure(e) => println(s"error = ${e.getMessage}")
  }

  Thread.sleep(7000)
  system.shutdown()
}
