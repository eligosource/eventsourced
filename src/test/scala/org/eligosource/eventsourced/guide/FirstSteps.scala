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

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object FirstSteps extends App {
  implicit val system = ActorSystem("guide")

  // create a journal
  val journal: ActorRef = LeveldbJournal(new File("target/guide-1"))

  // create an event-sourcing extension
  val extension = EventsourcingExtension(system, journal)

  // event-sourced processor
  class Processor(destination: ActorRef) extends Actor {
    var counter = 0

    def receive: Receive = {
      case msg: Message => {
        // update internal state
        counter = counter + 1
        // print received event and number of processed event messages so far
        println("[processor] event = %s (%d)" format (msg.event, counter))
        // send modified event message to destination
        destination ! msg.copy(event = "processed %d event messages so far" format counter)
      }
    }
  }

  // channel destination
  class Destination extends Actor {
    def receive: Receive = {
      case msg: Message => {
        // print event received from processor via channel
        println("[destination] event = '%s'" format msg.event)
        // confirm receipt of event message from channel
        msg.confirm()
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
  processor ! Message("foo")

  // wait for all messages to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()
}

