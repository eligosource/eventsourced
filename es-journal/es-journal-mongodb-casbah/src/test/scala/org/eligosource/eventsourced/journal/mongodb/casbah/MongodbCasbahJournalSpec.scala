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
package org.eligosource.eventsourced.journal.mongodb.casbah

import com.mongodb.casbah.Imports._

import org.eligosource.eventsourced.journal.common.PersistentJournalSpec
import org.eligosource.eventsourced.core.JournalProtocol.{ReplayInMsgs, WriteInMsg}
import org.eligosource.eventsourced.core.Message

import org.scalatest.BeforeAndAfterEach
import akka.actor.{Props, ActorSystem}
import org.eligosource.eventsourced.journal.common.JournalSpec.CommandTarget

class MongodbCasbahJournalSpec extends PersistentJournalSpec with MongodbSpecSupport with BeforeAndAfterEach {

  val dbName = "es2"
  val collName = "event"

  // Since multiple embedded instances will run, each one must have a different port.
  override def mongoPort = 54321

  def journalProps = MongodbCasbahJournalProps(MongoClient(mongoLocalHostName, mongoPort), dbName, collName)

  override def afterEach() {
    MongoClient(mongoLocalHostName, mongoPort)(dbName)(collName).dropCollection()
  }

  "fetch the highest casbah message counter as sequence nbr in correct order after 1000 message insert" in { fixture =>
    import fixture._

    val Upper = 1000
    val UpperPlus1 = Upper + 1

    for(x <- 1 to Upper) {
      journal ! WriteInMsg(1, Message("test-" + x), writeTarget)
    }
    journal ! ReplayInMsgs(1, 0, replayTarget)

    for(x <- 1 to Upper) {
      dequeue(replayQueue) { m => m.sequenceNr must be (x.toLong) }
    }

    system.shutdown()
    system.awaitTermination(duration)

    val anotherSystem = ActorSystem("test")
    val anotherJournal = journalProps.createJournal(anotherSystem)
    val anotherReplayTarget = anotherSystem.actorOf(Props(new CommandTarget(replayQueue)))

    anotherJournal ! WriteInMsg(1, Message("test-" + UpperPlus1), writeTarget)
    anotherJournal ! ReplayInMsgs(1, 0, anotherReplayTarget)

    for(x <- 1 to UpperPlus1) {
      dequeue(replayQueue) { m => m.sequenceNr must be (x.toLong) }
    }

    anotherSystem.shutdown()
    anotherSystem.awaitTermination(duration)
  }
}
