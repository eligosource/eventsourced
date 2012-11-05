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
package org.eligosource.eventsourced.journal

import java.io.File
import java.util.concurrent._

import scala.concurrent.duration._

import akka.actor._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._

abstract class JournalSpec extends WordSpec with MustMatchers {
  import JournalSpec._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journal = createJournal(journalDir)

    val writeQueue = new LinkedBlockingQueue[Message]
    val writeTarget = system.actorOf(Props(new CommandTarget(writeQueue)))

    val replayQueue = new LinkedBlockingQueue[Message]
    val replayTarget = system.actorOf(Props(new CommandTarget(replayQueue)))

    def dequeue(queue: LinkedBlockingQueue[Message])(p: Message => Unit) {
      p(queue.poll(5000, TimeUnit.MILLISECONDS))
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  def createJournal(journalDir: File)(implicit system: ActorSystem): ActorRef

  "A journal" must {
    "persist input messages" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteInMsg(1, Message("test-2"), writeTarget)

      journal ! ReplayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { _ must be(Message("test-1", sequenceNr = 1)) }
      dequeue(replayQueue) { _ must be(Message("test-2", sequenceNr = 2)) }
    }
    "persist messages with client-defined sequence numbers" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1", sequenceNr = 5), writeTarget, false)
      journal ! WriteInMsg(1, Message("test-2"), writeTarget)

      journal ! ReplayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { _ must be(Message("test-1", sequenceNr = 5)) }
      dequeue(replayQueue) { _ must be(Message("test-2", sequenceNr = 6)) }
    }
    "persist input messages and acknowledgements" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteAck(1, 1, 1)

      journal ! ReplayInMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { _ must be(Message("test-1", sequenceNr = 1, acks = List(1))) }
    }
    "persist input messages and acknowledgements along with output messages" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1"), writeTarget)
      journal ! WriteOutMsg(1, Message("test-2"), 1, 1, writeTarget)

      journal ! ReplayInMsgs(1, 0, replayTarget)
      journal ! ReplayOutMsgs(1, 0, replayTarget)

      dequeue(replayQueue) { _ must be(Message("test-1", sequenceNr = 1, acks = List(1))) }
      dequeue(replayQueue) { _ must be(Message("test-2", sequenceNr = 2)) }
    }
    "replay iput messages for n processors with a single command" in { fixture =>
      import fixture._

      journal ! WriteInMsg(1, Message("test-1a"), writeTarget)
      journal ! WriteInMsg(1, Message("test-1b"), writeTarget)

      journal ! WriteInMsg(2, Message("test-2a"), writeTarget)
      journal ! WriteInMsg(2, Message("test-2b"), writeTarget)

      journal ! WriteInMsg(3, Message("test-3a"), writeTarget)
      journal ! WriteInMsg(3, Message("test-3b"), writeTarget)

      journal ! BatchReplayInMsgs(List(
        ReplayInMsgs(1, 0L, replayTarget),
        ReplayInMsgs(3, 6L, replayTarget)
      ))

      dequeue(replayQueue) { _ must be(Message("test-1a", sequenceNr = 1)) }
      dequeue(replayQueue) { _ must be(Message("test-1b", sequenceNr = 2)) }
      dequeue(replayQueue) { _ must be(Message("test-3b", sequenceNr = 6)) }
    }
  }
}

object JournalSpec {
  class CommandTarget(queue: LinkedBlockingQueue[Message]) extends Actor {
    def receive = {
      case Written(msg) => queue.put(msg)
    }
  }
}

class InmenJournalSpec extends JournalSpec {
  def createJournal(journalDir: File)(implicit system: ActorSystem) =
    InmemJournal()
}

class LeveldbJournalPSSpec extends JournalSpec {
  def createJournal(journalDir: File)(implicit system: ActorSystem) =
    LeveldbJournal.processorStructured(journalDir)
}

class LeveldbJournalSSSpec extends JournalSpec {
  def createJournal(journalDir: File)(implicit system: ActorSystem) =
    LeveldbJournal.sequenceStructured(journalDir)
}

class JournalioJournalSpec extends JournalSpec {
  def createJournal(journalDir: File)(implicit system: ActorSystem) =
    JournalioJournal(journalDir)
}