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

import akka.actor._

import org.eligosource.eventsourced.core._

import FsmExample._

class FsmExample extends EventsourcingSpec[Fixture] {
  "An event-sourced decorated FSM" must {
    "recover its state from stored event messages" in { fixture =>
      import fixture._

      val door = configure()
      val askDoor = ask0(door) _

      extension.recover()

      askDoor(Message("open")) must be(DoorMoved(1))
      askDoor(Message("close")) must be(DoorMoved(2))
      askDoor(Message("close")) must be(DoorNotMoved("cannot close door in state Closed"))

      val recoveredDoor = configure()
      val askRecoveredDoor = ask0(recoveredDoor) _

      extension.recover()

      askRecoveredDoor(Message("open")) must be(DoorMoved(3))
      askRecoveredDoor(Message("close")) must be(DoorMoved(4))
    }
  }
}

object FsmExample {
  class Fixture  extends EventsourcingFixture[Any] {
    def configure(): ActorRef = {
      extension.processorOf(Props(decorator(1, system.actorOf(Props(new Door)), msg => msg.event)))
    }
  }

  sealed trait DoorState

  case object Open extends DoorState
  case object Closed extends DoorState

  case class DoorMoved(times: Int)
  case class DoorNotMoved(reason: String)

  class Door extends Actor with FSM[DoorState, Int] {
    startWith(Closed, 0)

    when(Closed) {
      case Event("open", counter) => {
        goto(Open) using(counter + 1) replying(DoorMoved(counter + 1))
      }
    }

    when(Open) {
      case Event("close", counter) => {
        goto(Closed) using(counter + 1) replying(DoorMoved(counter + 1))
      }
    }

    whenUnhandled {
      case Event(cmd, counter) => {
        stay replying(DoorNotMoved("cannot %s door in state %s" format (cmd, stateName)))
      }
    }
  }
}
