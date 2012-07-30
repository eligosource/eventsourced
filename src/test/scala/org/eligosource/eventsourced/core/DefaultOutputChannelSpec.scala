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

import java.io.File
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}

import akka.actor._
import akka.util.duration._

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

class DefaultOutputChannelSpec extends WordSpec with MustMatchers {
  import Channel._
  import Journaler._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")

    val dl = system.deadLetters

    val destinationQueue = new LinkedBlockingQueue[Message]
    val destination = system.actorOf(Props(new TestDesination(destinationQueue)))

    val writeAckListenerQueue = new LinkedBlockingQueue[WriteAck]
    val writeAckListener = system.actorOf(Props(new WriteAckListener(writeAckListenerQueue)))

    val journalDir = new File("target/journal")
    val journaler = system.actorOf(Props(new Journaler(journalDir)))
    val channel = system.actorOf(Props(new DefaultOutputChannel(1, 1, journaler)))

    channel ! SetDestination(destination)

    def dequeue[A](queue: LinkedBlockingQueue[A], timeout: Long = 5000): A = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }

    class TestDesination(blockingQueue: LinkedBlockingQueue[Message]) extends Actor {
      def receive = {
        case msg: Message => { blockingQueue.put(msg); sender ! () }
      }
    }
    class WriteAckListener(blockingQueue: LinkedBlockingQueue[WriteAck]) extends Actor {
      def receive = {
        case cmd: WriteAck => blockingQueue.put(cmd)
      }
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  "A default output channel" must {
    "buffer messages before initial delivery" in { fixture =>
      import fixture._

      channel ! Message("a")
      channel ! Message("b")

      channel ! Deliver

      dequeue(destinationQueue) must be (Message("a"))
      dequeue(destinationQueue) must be (Message("b"))
    }
    "not buffer messages after initial delivery" in { fixture =>
      import fixture._

      channel ! Message("a")

      channel ! Deliver

      channel ! Message("b")
      channel ! Message("c")

      dequeue(destinationQueue) must be (Message("a"))
      dequeue(destinationQueue) must be (Message("b"))
      dequeue(destinationQueue) must be (Message("c"))
    }
    "acknowledge messages by default" in { fixture =>
      import fixture._

      journaler ! SetCommandListener(Some(writeAckListener))

      channel ! Message("a", sequenceNr = 1)
      channel ! Deliver
      channel ! Message("b", sequenceNr = 2)

      val received = Set(
        dequeue(writeAckListenerQueue),
        dequeue(writeAckListenerQueue)
      )

      val expected = Set(
        WriteAck(1, 1, 1),
        WriteAck(1, 1, 2)
      )

      received must be (expected)
    }
    "not acknowledge messages on request" in { fixture =>
      import fixture._

      journaler ! SetCommandListener(Some(writeAckListener))

      channel ! Message("a", sequenceNr = 1, ack = false)
      channel ! Deliver
      channel ! Message("b", sequenceNr = 2, ack = false)
      channel ! Message("c", sequenceNr = 3)

      dequeue(writeAckListenerQueue) must be (WriteAck(1, 1, 3))
    }
  }
}
