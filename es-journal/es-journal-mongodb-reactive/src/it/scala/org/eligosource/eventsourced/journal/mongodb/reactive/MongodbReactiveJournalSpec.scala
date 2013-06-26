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

import de.flapdoodle.embed.mongo.{Command, MongodProcess, MongodExecutable, MongodStarter}
import de.flapdoodle.embed.mongo.config.{MongodConfig, RuntimeConfigBuilder}
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.config.io.ProcessOutput
import de.flapdoodle.embed.process.io.{NullProcessor, Processors}
import de.flapdoodle.embed.process.runtime.Network

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.journal.common.PersistentJournalSpec

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import reactivemongo.api.{DB, MongoConnection, MongoDriver}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


class MongodbReactiveJournalSpec extends PersistentJournalSpec with BeforeAndAfterEach with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val duration = 10 seconds

  val mongoVer = Version.V2_4_3
  val mongoLocalHostName = Network.getLocalHost.getCanonicalHostName
  val mongoLocalHostIPV6 = Network.localhostIsIPv6()
  val mongoDefaultPort = 54441

  var mongoStarter: MongodStarter = _
  var mongoExe: MongodExecutable = _
  var mongod: MongodProcess = _

  var connection: MongoConnection = _

  def journalProps = MongodbReactiveJournalProps(List(mongoLocalHostName + ":" + mongoDefaultPort))

  override def afterEach() {
    val db = DB(DefaultDatabaseName, connection)
    val collection = db(DefaultCollectionName)
    Await.result(collection.drop(), duration)
    Await.result(db.drop(), duration)
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
    mongoExe = mongoStarter.prepare(new MongodConfig(mongoVer, mongoDefaultPort, mongoLocalHostIPV6))
    mongod = mongoExe.start()

    val driver = new MongoDriver
    connection = driver.connection(List(mongoLocalHostName + ":" + mongoDefaultPort))
  }

  override def afterAll() = try {
    connection.askClose()
    mongod.stop()
    mongoExe.stop()
  } catch { case _: Throwable => /* ignore */ }

  "fetch the highest reactive message counter as sequence nbr in correct order" in { fixture =>
    import fixture._

    journal ! WriteInMsg(1, Message("test-1"), writeTarget)
    journal ! WriteInMsg(1, Message("test-2"), writeTarget)
    journal ! WriteInMsg(1, Message("test-3"), writeTarget)
    journal ! WriteInMsg(1, Message("test-4"), writeTarget)

    journal ! ReplayInMsgs(1, 0, replayTarget)

    dequeue(replayQueue) { m => m.sequenceNr must be (1L) }
    dequeue(replayQueue) { m => m.sequenceNr must be (2L) }
    dequeue(replayQueue) { m => m.sequenceNr must be (3L) }
    dequeue(replayQueue) { m => m.sequenceNr must be (4L) }
  }
}
