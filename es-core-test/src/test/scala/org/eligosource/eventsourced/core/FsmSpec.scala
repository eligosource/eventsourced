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
package org.eligosource.eventsourced.core

import akka.actor._

import org.eligosource.eventsourced.core.Journal._

import FsmSpec._

class FsmSpec extends EventsourcingSpec[Fixture] {
  "An event-sourced FSM" must {
    "recover its state from stored event messages" in { fixture =>
      import fixture._

      val door = configure()
      val completion = CommandListener(journal, 3) { case cmd: WriteAck => true }
      extension.recover()

      door ! Message("open")
      door ! Message("close")
      door ! Message("close")

      dequeue must be(DoorMoved(Open, 1))
      dequeue must be(DoorMoved(Closed, 2))
      dequeue must be(DoorNotMoved(Closed, "cannot close door"))

      completion.await()

      val recoveredDoor = configure()
      extension.recover()

      recoveredDoor ! Message("open")
      recoveredDoor ! Message("close")
      recoveredDoor ! Message("blah")

      dequeue must be(DoorMoved(Open, 3))
      dequeue must be(DoorMoved(Closed, 4))
      dequeue must be(NotSupported("blah"))
    }
  }
}

object FsmSpec {
  class Fixture  extends EventsourcingFixture[Any] {
    val destination = system.actorOf(Props(new Destination(queue) with Receiver with Confirm))

    def configure(): ActorRef = {
      extension.channelOf(DefaultChannelProps(1, destination))
      extension.processorOf(Props(new Door with Emitter with Eventsourced { val id = 1 } ))
    }
  }

  sealed trait DoorState

  case object Open extends DoorState
  case object Closed extends DoorState

  case class DoorMoved(state: DoorState, times: Int)
  case class DoorNotMoved(state: DoorState, cmd: String)
  case class NotSupported(cmd: Any)

  class Door extends Actor with FSM[DoorState, Int] { this: Emitter =>
    startWith(Closed, 0)

    when(Closed) {
      case Event("open", counter) => {
        emit(DoorMoved(Open, counter + 1))
        goto(Open) using(counter + 1)
      }
    }

    when(Open) {
      case Event("close", counter) => {
        emit(DoorMoved(Closed, counter + 1))
        goto(Closed) using(counter + 1)
      }
    }

    whenUnhandled {
      case Event(cmd @ ("open" | "close"), counter) => {
        emit(DoorNotMoved(stateName, "cannot %s door" format cmd))
        stay
      }
      case Event(cmd, counter) => {
        emit(NotSupported(cmd))
        stay
      }
    }

    def emit(event: Any) = emitter(1) forwardEvent event
  }

  class Destination(queue: java.util.Queue[Any]) extends Actor {
    def receive = {
      case event => queue.add(event)
    }
  }
}
