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
package org.eligosource.eventsourced.core

import akka.actor._

import MulticastSpec._

class MulticastSpec extends EventsourcingSpec[Fixture] {
  "A multicast processor" must {
    "forward received event messages to its targets" in { fixture =>
      import fixture._

      val m = multicast()

      m ! Message("test")

      dequeue() must be ("test")
      dequeue() must be ("test")
    }
    "forward received non-event messages to its targets" in { fixture =>
      import fixture._

      val m = multicast()
      m ! "blah"

      dequeue() must be ("blah")
      dequeue() must be ("blah")
    }
    "support message transformation" in { fixture =>
      import fixture._

      val m = multicast(msg => msg.copy(event = "mod: %s" format msg.event))

      m ! Message("test")

      dequeue() must be ("mod: test")
      dequeue() must be ("mod: test")
    }
    "preserve sender" in { fixture =>
      import fixture._

      val d = decorator()
      request(d)(Message("test")) must be("re: test")
      request(d)("blah")          must be("re: blah")
    }
  }
}

object MulticastSpec {
  class Fixture extends EventsourcingFixture[Any] {
    val destination = system.actorOf(Props(new Destination(queue) with Receiver with Confirm))

    val channel1 = extension.channelOf(DefaultChannelProps(1, destination))
    val channel2 = extension.channelOf(DefaultChannelProps(2, destination))

    def multicast(transformer: Message => Any = identity) =
      extension.processorOf(Props(org.eligosource.eventsourced.core.multicast(1, List(
        system.actorOf(Props(new Target(channel1) with Receiver)),
        system.actorOf(Props(new Target(channel2) with Receiver))), transformer)))

    def decorator(transformer: Message => Any = identity) =
      extension.processorOf(Props(org.eligosource.eventsourced.core.decorator(2,
        system.actorOf(Props(new Target(channel1) with Receiver)), transformer)))

    extension.recover()
  }

  class Target(channel: ActorRef) extends Actor { this: Receiver =>
    def receive = {
      case "blah" => channel forward Message("blah", ack = false)
      case event  => channel forward message
    }
  }

  class Destination(queue: java.util.Queue[Any]) extends Actor { this: Receiver =>
    def receive = {
      case event  => {
        queue.add(event)
        sender ! ("re: %s" format event)
      }
    }
  }
}
