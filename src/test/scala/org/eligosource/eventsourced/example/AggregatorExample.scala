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

import scala.concurrent._

import akka.actor._
import akka.pattern.ask

import org.eligosource.eventsourced.core._

import AggregatorExample._

class AggregatorExample extends EventsourcingSpec[Fixture] {
  "An event-sourced aggregator" must {
    "recover its state from stored event messages" in { fixture =>
      import fixture._

      {
        val aggregator = configure()

        extension.recover()

        // send InputAvailable event to event-sourced aggregator
        aggregator ! Message(InputAvailable("category-a", "input-1")) // no response expected
        aggregator ! Message(InputAvailable("category-a", "input-2")) // no response expected
        aggregator ! Message(InputAvailable("category-b", "input-7")) // no response expected

        // await aggregation response by aggregator to initiator
        val future = aggregator ? Message(InputAvailable("category-a", "input-3"))
        Await.result(future, timeout.duration) must be("aggregated 3 messages of category-a")

        // obtain output event message delivered to destination
        val delivered = dequeue()
        delivered.event must be(InputAggregated("category-a", List("input-1", "input-2", "input-3")))
        delivered.senderMessageId must be(Some("aggregated-1"))
      }

      {
        // replace old processors and channels with new ones
        val aggregator = configure()

        // recover processor state from journaled messages
        extension.recover()

        // now trigger the next aggregation (2 messages of category-b missing)
        aggregator ! Message(InputAvailable("category-b", "input-8")) // no response expected
        val future = aggregator ? Message(InputAvailable("category-b", "input-9"))

        // await next aggregation response by aggregator to initiator
        Await.result(future, timeout.duration) must be("aggregated 3 messages of category-b")

        // obtain next output event message delivered to destination
        val delivered = dequeue()
        delivered.event must be(InputAggregated("category-b", List("input-7", "input-8", "input-9")))
        delivered.senderMessageId must be(Some("aggregated-2"))
      }
    }
  }
}

object AggregatorExample {
  class Fixture extends EventsourcingFixture[Message] {
    val destination = system.actorOf(Props(new Destination(queue) with Receiver with Idempotent with Confirm))

    def configure(): ActorRef = {
      val processor = extension.processorOf(Props(new Aggregator with Emitter with Confirm with Eventsourced { val id = 1 } ))
      extension.channelOf(DefaultChannelProps(1, processor))
      extension.channelOf(ReliableChannelProps(2, destination))
      processor
    }
  }

  // Events
  case class InputAvailable(category: String, input: String)
  case class InputAggregated(category: String, inputs: List[String])

  // Event-sourced aggregator
  class Aggregator extends Actor { this: Emitter =>
    var inputAggregatedCounter = 0
    var inputs = Map.empty[String, List[String]] // category -> inputs

    def receive = {
      case InputAggregated(category, inputs) => {
        // count number of InputAggregated receivced
        inputAggregatedCounter = inputAggregatedCounter + 1
        // emit InputAggregated event to destination with sender message id containing the counted aggregations
        emitter(2) send { msg => msg.copy(senderMessageId = Some("aggregated-%d" format inputAggregatedCounter)) }
        // reply to initial sender that message has been aggregated
        sender ! "aggregated %d messages of %s".format(inputs.size, category)
      }
      case InputAvailable(category, input) => inputs = inputs.get(category) match {
        case Some(List(i2, i1)) => {
          // emit InputAggregated event to self when 3 events of same category exist
          emitter(1) forwardEvent InputAggregated(category, List(i1, i2, input))
          inputs - category
        }
        case Some(is) => inputs + (category -> (input :: is))
        case None => inputs + (category -> List(input))
      }
    }
  }

  class Destination(queue: java.util.Queue[Message]) extends Actor { this: Receiver =>
    def receive = {
      case event => queue.add(message)
    }
  }
}
