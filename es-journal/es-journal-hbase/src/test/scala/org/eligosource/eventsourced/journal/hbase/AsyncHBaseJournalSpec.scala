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

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import org.eligosource.eventsourced.journal.common.JournalSpec

class AsyncHBaseJournalSpec extends JournalSpec with HBaseCleanup with BeforeAndAfterEach with BeforeAndAfterAll {

  def journalProps = AsyncHBaseJournalProps("localhost:%d" format port)

  var port: Int = _
  var util: HBaseTestingUtility = _
  var admin: HBaseAdmin = _
  var client: HTable = _

  override def afterEach() {
    cleanup()
  }

  override def beforeAll() {
    util = new HBaseTestingUtility()
    util.startMiniCluster()
    port = util.getZkCluster.getClientPort

    CreateSchema(util.getConfiguration)

    client = new HTable(util.getConfiguration, TableName)
  }

  override def afterAll() = try {
    client.close()
    util.shutdownMiniCluster()
    util.cleanupTestDir()
  } catch { case _: Throwable => /* ignore */ }
}
