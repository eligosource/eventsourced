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
package org.eligosource.eventsourced.guide

import java.io.File

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object SenderReferences extends App {
  implicit val system = ActorSystem("guide")
  implicit val timeout = Timeout(5 seconds)

  import system.dispatcher

  // create a journal
  val journal: ActorRef = LeveldbJournal(new File("target/guide-3"))

  // create an event-sourcing extension
  val extension = EventsourcingExtension(system, journal)

  // event-sourced processor
  class Processor(destination: ActorRef) extends Actor {
    var counter = 0

    def receive = {
      case msg: Message => {
        // update internal state
        counter = counter + 1
        // print received event and number of processed event messages so far
        println("[processor] event = %s (%d)" format (msg.event, counter))
        // forward modified event message to destination (together with sender reference)
        destination forward msg.copy(event = "processed %d event messages so far" format counter)
      }
    }
  }

  // channel destination
  class Destination extends Actor {
    def receive = {
      case msg: Message => {
        // print event received from processor via channel
        println("[destination] event = '%s'" format msg.event)
        // confirm receipt of event message from channel
        msg.confirm()
        // reply to sender
        sender ! ("done processing event = %s (%d)" format msg.event)
      }
    }
  }

  // create channel destination
  val destination: ActorRef = system.actorOf(Props[Destination])

  // create and register a channel
  val channel: ActorRef = extension.channelOf(DefaultChannelProps(1, destination))

  // create and register event-sourced processor
  val processor: ActorRef = extension.processorOf(Props(new Processor(channel) with Eventsourced { val id = 1 } ))

  // recover registered processors by replaying journaled events
  extension.recover()

  // send event message to processor (will be journaled)
  // and asynchronously receive response (will not be journaled)
  processor ? Message("foo") onSuccess {
    case response => println(response)
  }

  // wait for all messages to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()
}

