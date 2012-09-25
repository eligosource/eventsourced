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

import java.io.File
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor._
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Decorator.Emit
import org.eligosource.eventsourced.journal.LeveldbJournal

class FsmExample extends WordSpec with MustMatchers {
  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journal = LeveldbJournal(journalDir)

    val queue = new LinkedBlockingQueue[Any]
    val destination = system.actorOf(Props(new Destination(queue) with Receiver))

    def createExampleContext = Context(journal)
      .addChannel("dest", destination)
      .addProcessor(1, decorator(system.actorOf(Props(new Door))))

    def dequeue(timeout: Long = 5000): Any = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try {
      test(fixture)
    } finally {
      fixture.shutdown()
    }
  }

  "An event-sourced context" must {
    "recover FSM state from stored event messages" in { fixture =>
      import fixture._

      val context = createExampleContext.init()
      val door = context.processors(1)

      door ! Message("open")
      door ! Message("close")
      door ! Message("close")

      dequeue() must be (DoorMoved(1))
      dequeue() must be (DoorMoved(2))
      dequeue() must be (DoorNotMoved("cannot close door in state Closed"))

      val recoveredContext = createExampleContext.init()
      val recoveredDoor = recoveredContext.processors(1)

      recoveredDoor ! Message("open")
      recoveredDoor ! Message("close")

      dequeue() must be (DoorMoved(3))
      dequeue() must be (DoorMoved(4))
    }
  }

  class Door extends Actor with FSM[DoorState, Int] {
    startWith(Closed, 0)

    when(Closed) {
      case Event("open", counter) => goto(Open) using(counter + 1) replying(Emit("dest", DoorMoved(counter + 1)))
    }

    when(Open) {
      case Event("close", counter) => goto(Closed) using(counter + 1) replying(Emit("dest", DoorMoved(counter + 1)))
    }

    whenUnhandled {
      case Event(cmd, counter) => {
        stay replying(Emit("dest", DoorNotMoved("cannot %s door in state %s" format (cmd, stateName))))
      }
    }
  }

  class Destination(queue: LinkedBlockingQueue[Any]) extends Actor {
    def receive = {
      case event => queue.put(event)
    }
  }

}

sealed trait DoorState

case object Open extends DoorState
case object Closed extends DoorState

case class DoorMoved(times: Int)
case class DoorNotMoved(reason: String)
