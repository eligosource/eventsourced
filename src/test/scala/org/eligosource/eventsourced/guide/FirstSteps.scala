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
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object FirstSteps extends App {
  implicit val system = ActorSystem("example")

  // create a journal
  val journal: ActorRef = LeveldbJournal(new File("target/example-1"))

  // create an event-sourcing extension
  val extension = EventsourcingExtension(system, journal)

  // event-sourced processor definition
  class Processor extends Actor {
    var counter = 0;

    def receive = {
      case msg: Message => {
        counter = counter + 1
        println("received message #%d" format counter)
      }
    }
  }

  // create and register event-sourced processor
  val processor: ActorRef = extension.processorOf(ProcessorProps(1, new Processor with Eventsourced))

  // recover registered processors by replaying journaled events
  extension.recover()

  // send event message to processor (will be journaled)
  processor ! Message("foo")

  // send non-event message to processor (will not be journaled)
  processor ! "bar"

  // wait for all messages to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()
}
