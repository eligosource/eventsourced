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
package org.eligosource.eventsourced.guide

import java.io.File

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps

object StackableTraits extends App {
  implicit val system = ActorSystem("guide")

  // create a journal
  val journal: ActorRef = LeveldbJournalProps(new File("target/guide-2"), native = false).createJournal

  // create an event-sourcing extension
  val extension = EventsourcingExtension(system, journal)

  // event-sourced processor
  class Processor extends Actor { this: Emitter =>
    var counter = 0

    def receive = {
      case event => {
        // update internal state
        counter = counter + 1
        // print received event and number of processed events so far
        println("[processor] event = %s (%d)" format (event, counter))
        // send new event to destination channel
        emitter("destination") sendEvent ("processed %d events so far" format counter)
      }
    }
  }

  // channel destination
  class Destination extends Actor {
    def receive = {
      case event => {
        // print event received from processor via channel
        println("[destination] event = '%s'" format event)
      }
    }
  }

  // create and register event-sourced processor
  val processor: ActorRef = extension.processorOf(Props(new Processor with Emitter with Eventsourced { val id = 1 } ))

  // create channel destination
  val destination: ActorRef = system.actorOf(Props(new Destination with Receiver with Confirm))

  // create and register a named channel
  extension.channelOf(DefaultChannelProps(1, destination).withName("destination"))

  // recover registered processors by replaying journaled events
  extension.recover()

  // send event message to processor (will be journaled)
  processor ! Message("foo")

  // wait for all messages to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()
}

