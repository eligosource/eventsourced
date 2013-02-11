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
package org.eligosource.eventsourced.journal.common

import java.io.File
import java.util.concurrent._

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.serialization.Serializer
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._

abstract class JournalSpec extends WordSpec with MustMatchers {
  import JournalSpec._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test", ConfigFactory.load("persist"))
    implicit val duration = 5 seconds
    implicit val timeout = Timeout(duration)

    val journal = Journal(journalProps)

    val writeQueue = new LinkedBlockingQueue[Message]
    val writeTarget = system.actorOf(Props(new CommandTarget(writeQueue)))

    val replayQueue = new LinkedBlockingQueue[Message]
    val replayTarget = system.actorOf(Props(new CommandTarget(replayQueue)))

    def dequeue(queue: LinkedBlockingQueue[Message])(p: Message => Unit) {
      p(queue.poll(5000, TimeUnit.MILLISECONDS))
    }

    def replayInMsgs(processorId: Int, fromSequenceNr: Long, target: ActorRef) {
      Await.result(journal ? ReplayInMsgs(processorId, fromSequenceNr, target), duration)
    }

    def replayOutMsgs(channelId: Int, fromSequenceNr: Long, target: ActorRef) {
      journal ! ReplayOutMsgs(channelId, fromSequenceNr, target)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(duration)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  def journalProps: JournalProps

  "A journal" must {
    "persist and timestamp input messages" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteInMsg(1, Message("test-2"), writeTarget)

      replayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { m => m must be(Message("test-1", sequenceNr = 1, timestamp = m.timestamp)); m.timestamp must be > (0L) }
      dequeue(replayQueue) { m => m must be(Message("test-2", sequenceNr = 2, timestamp = m.timestamp)); m.timestamp must be > (0L)  }
    }
    "persist but not timestamp output messages" in { fixture =>
      import fixture._

      journal ! WriteOutMsg(1, Message("test-1"), 1, SkipAck, writeTarget)
      journal ! WriteOutMsg(1, Message("test-2"), 1, SkipAck, writeTarget)

      replayOutMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { m => m must be(Message("test-1", sequenceNr = 1, timestamp = 0L)) }
      dequeue(replayQueue) { m => m must be(Message("test-2", sequenceNr = 2, timestamp = 0L)) }
    }
    "persist messages with client-defined sequence numbers" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1", sequenceNr = 5), writeTarget, false)
      journal ! WriteInMsg(1, Message("test-2"), writeTarget)

      replayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { m => m must be(Message("test-1", sequenceNr = 5, timestamp = m.timestamp)) }
      dequeue(replayQueue) { m => m must be(Message("test-2", sequenceNr = 6, timestamp = m.timestamp)) }
    }
    "persist input messages and acknowledgements" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteAck(1, 1, 1)

      replayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { m => m must be(Message("test-1", sequenceNr = 1, acks = List(1), timestamp = m.timestamp)) }
    }
    "persist input messages and acknowledgements along with output messages" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteOutMsg(1, Message("test-2"), 1, 1, writeTarget)

      replayInMsgs(1, 0, replayTarget)
      replayOutMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { m => m must be(Message("test-1", sequenceNr = 1, acks = List(1), timestamp = m.timestamp)) }
      dequeue(replayQueue) { m => m must be(Message("test-2", sequenceNr = 2, timestamp = 0L)) }
    }
    "replay iput messages for n processors with a single command" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1a"), writeTarget)
      journal ! WriteInMsg(1, Message("test-1b"), writeTarget)

      journal ! WriteInMsg(2, Message("test-2a"), writeTarget)
      journal ! WriteInMsg(2, Message("test-2b"), writeTarget)

      journal ! WriteInMsg(3, Message("test-3a"), writeTarget)
      journal ! WriteInMsg(3, Message("test-3b"), writeTarget)

      Await.result(journal ? BatchReplayInMsgs(List(
        ReplayInMsgs(1, 0L, replayTarget),
        ReplayInMsgs(3, 6L, replayTarget)
      )), duration)

      dequeue(replayQueue) { m => m must be(Message("test-1a", sequenceNr = 1, timestamp = m.timestamp)) }
      dequeue(replayQueue) { m => m must be(Message("test-1b", sequenceNr = 2, timestamp = m.timestamp)) }
      dequeue(replayQueue) { m => m must be(Message("test-3b", sequenceNr = 6, timestamp = m.timestamp)) }
    }
    "tolerate phantom acknowledgements" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteAck(1, 1, 1)
      journal ! WriteAck(1, 1, 2)

      replayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { m => m must be(Message("test-1", sequenceNr = 1, acks = List(1), timestamp = m.timestamp)) }
    }
  }
}

object JournalSpec {
  case class CustomEvent(s: String)

  class CustomEventSerializer extends Serializer {
    def identifier = 42
    def includeManifest = true

    def toBinary(o: AnyRef) = o match {
      case CustomEvent(s) => s.toUpperCase.getBytes("UTF-8")
      case _ => throw new IllegalArgumentException("require CustomEvent")
    }

    def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) = manifest match {
      case Some(c) if (c == classOf[CustomEvent]) => CustomEvent(new String(bytes, "UTF-8"))
      case _ => throw new IllegalArgumentException("require CustomEvent")
    }
  }

  class CommandTarget(queue: LinkedBlockingQueue[Message]) extends Actor {
    def receive = {
      case Written(msg) => queue.put(msg)
    }
  }
}

