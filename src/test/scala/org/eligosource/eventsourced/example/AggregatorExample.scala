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
import akka.dispatch._
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

class AggregatorExample extends WordSpec with MustMatchers {
  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journal = LeveldbJournal(journalDir)

    val queue = new LinkedBlockingQueue[Message]
    val destination = system.actorOf(Props(new Destination(queue) with Receiver))

    implicit val extension = EventsourcingExtension(system, journal)

    def configureExtension(): ActorRef = {
      val processor = extension.processorOf(ProcessorProps(1, new Aggregator with Emitter with Eventsourced))
      extension.channelOf(DefaultChannelProps(1, processor).withName("self"))
      extension.channelOf(ReliableChannelProps(2, destination).withName("dest"))
      processor
    }

    def dequeue(timeout: Long = 5000): Message = {
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

  "An event-sourced aggregator" must {
    "recover its state from stored event messages" in { fixture =>
        import fixture._

        {
          val aggregator = configureExtension()

          extension.recover()

          // send InputAvailable event to event-sourced aggregator
          aggregator ! Message(InputAvailable("category-a", "input-1")) // no response expected
          aggregator ! Message(InputAvailable("category-a", "input-2")) // no response expected
          aggregator ! Message(InputAvailable("category-b", "input-7")) // no response expected

          // await aggregation response by aggregator to initiator
          val future = aggregator ?? Message(InputAvailable("category-a", "input-3"))
          Await.result(future, timeout.duration) must be("aggregated 3 messages of category-a")

          // obtain output event message delivered to destination
          val delivered = dequeue()
          delivered.event must be(InputAggregated("category-a", List("input-1", "input-2", "input-3")))
          delivered.senderMessageId must be(Some("aggregated-1"))
        }

        {
          // replace old processors and channels with new ones
          val aggregator = configureExtension()

          // recover processor state from journaled messages
          extension.recover()

          // now trigger the next aggregation (2 messages of category-b missing)
          aggregator ! Message(InputAvailable("category-b", "input-8")) // no response expected
          val future = aggregator ?? Message(InputAvailable("category-b", "input-9"))

          // await next aggregation response by aggregator to initiator
          Await.result(future, timeout.duration) must be("aggregated 3 messages of category-b")

          // obtain next output event message delivered to destination
          val delivered = dequeue()
          delivered.event must be(InputAggregated("category-b", List("input-7", "input-8", "input-9")))
          delivered.senderMessageId must be(Some("aggregated-2"))
        }
    }
  }

  // Event-sourced aggregator
  class Aggregator extends Actor { this: Emitter =>
    var inputAggregatedCounter = 0
    var inputs = Map.empty[String, List[String]] // category -> inputs

    def receive = {
      case InputAggregated(category, inputs) => {
        // count number of InputAggregated receivced
        inputAggregatedCounter = inputAggregatedCounter + 1
        // emit InputAggregated event to destination with sender message id containing the counted aggregations
        emitter("dest").emit(_.copy(senderMessageId = Some("aggregated-%d" format inputAggregatedCounter)))
        // reply to initial sender that message has been aggregated
        initiator ! "aggregated %d messages of %s".format(inputs.size, category)
      }
      case InputAvailable(category, input) => inputs = inputs.get(category) match {
        case Some(List(i2, i1)) => {
          // emit InputAggregated event to self when 3 events of same category exist
          emitter("self").emitEvent(InputAggregated(category, List(i1, i2, input)))
          inputs - category
        }
        case Some(is) => inputs + (category -> (input :: is))
        case None => inputs + (category -> List(input))
      }
    }
  }

  class Destination(queue: LinkedBlockingQueue[Message]) extends Actor { this: Receiver =>
    def receive = {
      case event => queue.put(message)
    }
  }
}

case class InputAvailable(category: String, input: String)
case class InputAggregated(category: String, inputs: List[String])

