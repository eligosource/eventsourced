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
package org.eligosource.eventsourced.journal.leveldb

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.journal.common.JournalSpec

abstract class LeveldbJournalPSSpec extends JournalSpec {
  import JournalSpec._

  "persist input messages with a custom event serializer" in { fixture =>
    import fixture._

    journal ! WriteInMsg(1, Message(CustomEvent("test-1")), writeTarget)
    journal ! WriteInMsg(1, Message(CustomEvent("test-2")), writeTarget)

    journal ! ReplayInMsgs(1, 0, replayTarget)

    dequeue(replayQueue) { m => m must be(Message(CustomEvent("TEST-1"), sequenceNr = 1, timestamp = m.timestamp)) }
    dequeue(replayQueue) { m => m must be(Message(CustomEvent("TEST-2"), sequenceNr = 2, timestamp = m.timestamp)) }
  }
  "persist output messages with a custom event serializer" in { fixture =>
    import fixture._

    journal ! WriteOutMsg(1, Message(CustomEvent("test-3")), 1, SkipAck, writeTarget)
    journal ! WriteOutMsg(1, Message(CustomEvent("test-4")), 1, SkipAck, writeTarget)

    journal ! ReplayOutMsgs(1, 0, replayTarget)

    dequeue(replayQueue) { m => m must be(Message(CustomEvent("TEST-3"), sequenceNr = 1, timestamp = 0L)) }
    dequeue(replayQueue) { m => m must be(Message(CustomEvent("TEST-4"), sequenceNr = 2, timestamp = 0L)) }
  }
}

object LeveldbJournalPSSpec {
  val journalDir = new File("es-journal/es-journal-leveldb/target/journal")
}

class LeveldbJournalPSDefaultSpec extends LeveldbJournalPSSpec with BeforeAndAfterEach {
  def journalProps = LeveldbJournalProps(LeveldbJournalPSSpec.journalDir)

  override def afterEach() {
    FileUtils.deleteDirectory(LeveldbJournalPSSpec.journalDir)
  }
}

class LeveldbJournalSSSpec extends JournalSpec with BeforeAndAfterEach {
  def journalProps = LeveldbJournalProps(LeveldbJournalPSSpec.journalDir).withSequenceStructure

  override def afterEach() {
    FileUtils.deleteDirectory(LeveldbJournalPSSpec.journalDir)
  }
}
