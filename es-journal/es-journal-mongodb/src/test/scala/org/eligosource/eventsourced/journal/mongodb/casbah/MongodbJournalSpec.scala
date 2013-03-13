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

import org.scalatest.BeforeAndAfterEach
import com.mongodb.casbah.Imports._
import org.eligosource.eventsourced.journal.common.JournalSpec
import de.flapdoodle.embed.mongo.config.MongodConfig
import de.flapdoodle.embed.mongo.distribution._
import de.flapdoodle.embed.process.runtime.Network
import de.flapdoodle.embed.mongo.{MongodProcess, MongodExecutable, MongodStarter}

class MongodbJournalSpec extends JournalSpec with BeforeAndAfterEach {

  val embedMongoDBName = "casbah"
  val embedMongoDBColl = "journal"
  val embedMongoDBConnPort = 12345
  val embedMongoDBVer = Version.V2_2_1

  var runtime: MongodStarter = null
  var mongodExe: MongodExecutable = null
  var mongod: MongodProcess = null
  var mongoConn: MongoConnection = null

  def journalProps = MongodbJournalProps(journalColl)

  def journalColl: MongoCollection = mongoConn(embedMongoDBName)(embedMongoDBColl)

  override def beforeEach() {
    runtime = MongodStarter.getDefaultInstance
    mongodExe = runtime.prepare(new MongodConfig(embedMongoDBVer, embedMongoDBConnPort, Network.localhostIsIPv6()))
    mongod = mongodExe.start()
    mongoConn = MongoConnection(Network.getLocalHost.getCanonicalHostName, embedMongoDBConnPort)
  }

  override def afterEach() {
    journalColl.dropCollection()
    mongoConn.dropDatabase(embedMongoDBName)
    mongoConn.close()
    mongod.stop()
    mongodExe.stop()
  }
}
