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

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.eligosource.eventsourced.core.StressSpec._

class StressSpec  extends EventsourcingSpec[Fixture] {
  "An event-sourced application" when {
    "using default channels" should {
      "be able to deal with reasonable load" in { fixture =>
        import fixture._

        val processor = configure(reliable = false)
        extension.recover()

        stress(processor, throttle = 40)

        queue.poll(30, TimeUnit.SECONDS) must be(cycles)

        extension.recover()

        //queue.poll(30, TimeUnit.SECONDS) must be(cycles)

      }
    }
    "using reliable channels" should {
      "be able to deal with reasonable load" in { fixture =>
        import fixture._

        val processor = configure(reliable = true)

        extension.recover()

        stress(processor, throttle = 70)

        queue.poll(100, TimeUnit.SECONDS) must be(cycles)

        extension.recover()

        //queue.poll(30, TimeUnit.SECONDS) must be(cycles)
      }
    }
  }
}

object StressSpec {
  val cycles = 5000

  class Fixture  extends EventsourcingFixture[Any] {
    val destination = system.actorOf(Props(new Destination(queue) with Receiver with Confirm))

    val reliableChannel = extension.channelOf(ReliableChannelProps(1, destination))
    val defaultChannel = extension.channelOf(DefaultChannelProps(2, destination))

    def configure(reliable: Boolean): ActorRef = {
      val channel = if (reliable) reliableChannel else defaultChannel
      extension.processorOf(Props(new Processor(channel) with Eventsourced { val id = 1 } ))
    }
  }

  def stress(processor: ActorRef, throttle: Long)(implicit timeout: Timeout, system: ActorSystem) {
    import system.dispatcher

    val start = System.nanoTime()
    1 to cycles foreach { i =>
      if (i % 10 == 0) Thread.sleep(throttle)
      val nanos = System.nanoTime()
      processor ? Message(i) onSuccess {
        case r: Int => if (r % 10 == 0) {
          val now = System.nanoTime()

          val latency = (now - nanos) / 1e6
          val throughput = r * 1e9 / (now - start)

          // print some statistics ...
          println("throughput = %.0f msgs/sec, latency of response %d = %.2f ms" format (throughput, r, latency))
        }
      }
    }
  }

  class Processor(channel: ActorRef) extends Actor {
    def receive = {
      case msg: Message => channel forward msg
    }
  }

  class Destination(queue: java.util.Queue[Any]) extends Actor { this: Receiver =>
    def receive = {
      case ctr: Int => {
        sender ! ctr
        if (ctr == cycles) queue.add(ctr)
      }
    }
  }
}
