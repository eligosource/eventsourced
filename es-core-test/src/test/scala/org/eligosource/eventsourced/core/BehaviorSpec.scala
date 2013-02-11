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

import BehaviorSpec._

class BehaviorSpec extends EventsourcingSpec[Fixture] {
  "A eventsourced processor" must {
    "can change behavior without loosing event-sourcing functionality" in { fixture =>
      import fixture._

      processor ! Message("foo")
      processor ! Message("bar")
      processor ! Message("baz")

      dequeue() must be ("foo (1)")
      dequeue() must be ("bar (2)")
      dequeue() must be ("baz (3)")

    }
    "call unhandled() for messages for which receive() is not defined" in { fixture =>
      import fixture._

      processor ! Message("xyz")

      dequeue() must be ("unhandled (1)")
    }
  }
}

object BehaviorSpec {
  class Fixture extends EventsourcingFixture[Any] {
    val destination = system.actorOf(Props(new Destination(queue) with Receiver with Confirm))
    val processor = extension.processorOf(Props(new Processor(destination) with Receiver with Eventsourced { val id = 1 } ))
  }

  class Processor(destination: ActorRef) extends Actor { this: Receiver =>
    val changed: Receive = {
      case "bar" => { destination ! ("bar (%d)" format sequenceNr); unbecome() }
    }

    def receive: Receive = {
      case "foo" => { destination ! ("foo (%d)" format sequenceNr); become(changed) }
      case "baz" => { destination ! ("baz (%d)" format sequenceNr) }
    }

    override def unhandled(msg: Any) = msg match {
      case s: String => { destination ! ("unhandled (%d)" format sequenceNr) }
      case _         => super.unhandled(msg)
    }
  }

  class Destination(queue: java.util.Queue[Any]) extends Actor {
    def receive: Receive = {
      case msg  => queue.add(msg)
    }
  }
}
