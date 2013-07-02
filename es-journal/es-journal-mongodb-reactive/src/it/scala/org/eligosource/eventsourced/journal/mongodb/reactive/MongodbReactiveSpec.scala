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
package org.eligosource.eventsourced.journal.mongodb.reactive

import java.io.File

import akka.actor.{Props, ActorSystem}

import de.flapdoodle.embed.mongo.{Command, MongodProcess, MongodExecutable, MongodStarter}
import de.flapdoodle.embed.mongo.config.{MongodConfig, RuntimeConfigBuilder}
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.config.io.ProcessOutput
import de.flapdoodle.embed.process.io.{NullProcessor, Processors}
import de.flapdoodle.embed.process.runtime.Network

import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.conf.Configuration

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.journal.common.JournalSpec.CommandTarget
import org.eligosource.eventsourced.journal.common.{PersistentReplaySpec, PersistentJournalSpec}

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

import reactivemongo.api.{DB, MongoConnection, MongoDriver}
import reactivemongo.core.commands.GetLastError

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

trait MongodbReactiveSpec extends BeforeAndAfterEach with BeforeAndAfterAll { self: Suite =>

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val waitUpTo = 10 seconds

  val mongoVer = Version.V2_4_3
  val mongoLocalHostName = Network.getLocalHost.getCanonicalHostName
  val mongoLocalHostIPV6 = Network.localhostIsIPv6()
  def mongoPort: Int

  var mongoStarter: MongodStarter = _
  var mongoExe: MongodExecutable = _
  var mongod: MongodProcess = _

  var driver: MongoDriver = _
  var connection: MongoConnection = _

  def journalProps: MongodbReactiveJournalProps

  override def afterEach() {
    val db = DB(DefaultDatabaseName, connection)
    val collection = db(DefaultCollectionName)
    Await.result(collection.drop(), waitUpTo)
    Await.result(db.drop(), waitUpTo)
  }

  override def beforeAll() {
    // Used to filter out console output messages.
    val processOutput = new ProcessOutput(Processors.named("[mongod>]", new NullProcessor),
      Processors.named("[MONGOD>]", new NullProcessor), Processors.named("[console>]", new NullProcessor))

    val runtimeConfig = new RuntimeConfigBuilder()
      .defaults(Command.MongoD)
      .processOutput(processOutput)
      .build()

    // Startup embedded mongodb.
    mongoStarter = MongodStarter.getInstance(runtimeConfig)
    mongoExe = mongoStarter.prepare(new MongodConfig(mongoVer, mongoPort, mongoLocalHostIPV6))
    mongod = mongoExe.start()

    driver = new MongoDriver
    connection = driver.connection(List(mongoLocalHostName + ":" + mongoPort))
  }

  override def afterAll() = try {
    connection.askClose()
    driver.close()
    mongod.stop()
    mongoExe.stop()
  } catch { case _: Throwable => /* ignore */ }
}

class MongodbReactiveJournalSpec extends PersistentJournalSpec with MongodbReactiveSpec {

  def mongoPort = 55000
  def journalProps = MongodbReactiveJournalProps(List(mongoLocalHostName + ":" + mongoPort))

  "fetch the highest reactive message counter as sequence nbr in correct order after 1000 messages" in { fixture =>
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

class MongodbReactiveJournalWithWriteConcernSpec extends PersistentJournalSpec with MongodbReactiveSpec {

  def mongoPort = 56000
  def journalProps = MongodbReactiveJournalProps(List(mongoLocalHostName + ":" + mongoPort), writeConcern = GetLastError(fsync = true))

  "fetch the highest reactive message counter as sequence nbr in correct order after 1000 messages" in { fixture =>
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

class MongodbReactiveReplaySpec extends PersistentReplaySpec with MongodbReactiveSpec {
  val snapshotFileSystemRoot = "es-journal/es-journal-mongodb-reactive/target/journal"
  val snapshotFilesystem = FileSystem.getLocal(new Configuration)

  snapshotFilesystem.setWorkingDirectory(new Path(snapshotFileSystemRoot))

  def mongoPort = 57000
  def journalProps = MongodbReactiveJournalProps(List(mongoLocalHostName + ":" + mongoPort), snapshotFilesystem = snapshotFilesystem)

  override def afterEach() {
    FileUtils.deleteDirectory(new File(snapshotFileSystemRoot))
    super.afterEach()
  }

}
