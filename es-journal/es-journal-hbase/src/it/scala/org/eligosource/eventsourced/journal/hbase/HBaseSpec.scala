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
package org.eligosource.eventsourced.journal.hbase

import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client._

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

import org.eligosource.eventsourced.journal.common.{PersistentReplaySpec, PersistentJournalSpec}

trait HBaseSpec extends HBaseCleanup with BeforeAndAfterEach with BeforeAndAfterAll { self: Suite =>
  var port: Int = 0
  var util: HBaseTestingUtility = _
  var admin: HBaseAdmin = _
  var client: HTable = _

  def zookeeperQuorum = "localhost:%d" format port
  def journalProps = {
    HBaseJournalProps(zookeeperQuorum)
      .withReplayChunkSize(8)
      .withSnapshotFilesystem(util.getTestFileSystem)
  }

  override def afterEach() {
    cleanup()
  }

  override def beforeAll() {
    util = new HBaseTestingUtility()
    util.startMiniCluster()
    port = util.getZkCluster.getClientPort

    CreateTable(util.getConfiguration, DefaultTableName, DefaultPartitionCount)
    client = new HTable(util.getConfiguration, DefaultTableName)
  }

  override def afterAll() = try {
    client.close()
    util.shutdownMiniCluster()
    util.cleanupTestDir()
  } catch { case _: Throwable => /* ignore */ }
}

class HBaseJournalSpec extends PersistentJournalSpec with HBaseSpec
class HBaseReplaySpec extends PersistentReplaySpec with HBaseSpec
