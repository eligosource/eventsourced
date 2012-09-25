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

import org.eligosource.eventsourced.journal.LeveldbJournal

class DefaultChannelSpec extends WordSpec with MustMatchers {
  import Channel._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")

    val dl = system.deadLetters

    val destinationQueue = new LinkedBlockingQueue[Message]
    val successDestination = system.actorOf(Props(new TestDestination(destinationQueue) with Responder))
    def failureDestination(failAtEvent: Any) = system.actorOf(Props(new FailureDestination(destinationQueue, failAtEvent) with Responder))

    val replyDestinationQueue = new LinkedBlockingQueue[Message]
    val successReplyDestination = system.actorOf(Props(new TestDestination(replyDestinationQueue) with Responder))
    def failureReplyDestination(failAtEvent: Any) = system.actorOf(Props(new FailureDestination(replyDestinationQueue, failAtEvent) with Responder))

    val writeAckListenerQueue = new LinkedBlockingQueue[WriteAck]
    val writeAckListener = system.actorOf(Props(new WriteAckListener(writeAckListenerQueue)))

    val journalDir = new File("target/journal")
    val journal = LeveldbJournal(journalDir)
    val channel = system.actorOf(Props(new DefaultChannel(1, journal)))

    channel ! SetDestination(successDestination)

    def message(event: Any, sequenceNr: Long = 0L, ack: Boolean = true) =
      Message(event, sequenceNr = sequenceNr, ack = ack, processorId = 1)

    def dequeue[A](queue: LinkedBlockingQueue[A], timeout: Long = 5000): A = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }

    class TestDestination(blockingQueue: LinkedBlockingQueue[Message]) extends Actor { this: Responder =>
      def receive = {
        case event => { blockingQueue.put(message); responder.sendEvent("re: %s" format event) }
      }
    }

    class FailureDestination(queue: LinkedBlockingQueue[Message], failAtEvent: Any) extends Actor { this: Responder =>
      def receive = {
        case event => if (event == failAtEvent) {
          responder.sendFailure(new Exception("test"))
        } else {
          queue.put(message)
          responder.send(msg => msg)
        }
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

  "A default channel" must {
    "buffer messages before initial delivery" in { fixture =>
      import fixture._

      channel ! message("a")
      channel ! message("b")

      channel ! Deliver

      dequeue(destinationQueue) must be (message("a"))
      dequeue(destinationQueue) must be (message("b"))
    }
    "not buffer messages after initial delivery" in { fixture =>
      import fixture._

      channel ! message("a")

      channel ! Deliver

      channel ! message("b")
      channel ! message("c")

      dequeue(destinationQueue) must be (message("a"))
      dequeue(destinationQueue) must be (message("b"))
      dequeue(destinationQueue) must be (message("c"))
    }
    "acknowledge messages by default" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeAckListener))

      channel ! message("a", 1)
      channel ! Deliver
      channel ! message("b", 2)

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

      journal ! SetCommandListener(Some(writeAckListener))

      channel ! message("a", 1, false)
      channel ! Deliver
      channel ! message("b", 2, false)
      channel ! message("c", 3)

      dequeue(writeAckListenerQueue) must be (WriteAck(1, 1, 3))
    }
    "acknowledge messages after delivery to a reply destination" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeAckListener))

      channel ! SetReplyDestination(successReplyDestination)
      channel ! message("a", 1)
      channel ! Deliver
      channel ! message("b", 2)

      dequeue(destinationQueue) must be (message("a", 1))
      dequeue(destinationQueue) must be (message("b", 2))

      val receivedReplies = Set(
        dequeue(replyDestinationQueue),
        dequeue(replyDestinationQueue)
      )

      val receivedAcks = Set(
        dequeue(writeAckListenerQueue),
        dequeue(writeAckListenerQueue)
      )

      val expectedReplies = Set(
        message("re: a", 1),
        message("re: b", 2)
      )

      val expectedAcks = Set(
        WriteAck(1, 1, 1),
        WriteAck(1, 1, 2)
      )

      receivedReplies must be (expectedReplies)
      receivedAcks must be (expectedAcks)
    }
    "not acknowledge messages after delivery failure to a destination" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeAckListener))

      channel ! SetDestination(failureDestination("a"))
      channel ! SetReplyDestination(successReplyDestination)
      channel ! message("a", 1)
      channel ! Deliver
      channel ! message("b", 2)

      dequeue(replyDestinationQueue) must be (message("b", 2))
      dequeue(writeAckListenerQueue) must be (WriteAck(1, 1, 2))
    }
    "not acknowledge messages after delivery failure to a reply destination" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeAckListener))

      channel ! SetReplyDestination(failureReplyDestination("re: a"))
      channel ! message("a", 1)
      channel ! Deliver
      channel ! message("b", 2)

      dequeue(replyDestinationQueue) must be (message("re: b", 2))
      dequeue(writeAckListenerQueue) must be (WriteAck(1, 1, 2))
    }
  }
}
