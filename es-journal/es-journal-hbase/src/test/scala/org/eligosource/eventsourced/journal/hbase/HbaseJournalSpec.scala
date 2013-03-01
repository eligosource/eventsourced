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

class HbaseJournalSpec extends JournalSpec with BeforeAndAfterEach with BeforeAndAfterAll {
  val journalProps = HBaseJournalProps(HBaseConfiguration.create())

  var util: HBaseTestingUtility = _
  var admin: HBaseAdmin = _
  var client: HTable = _

  override def afterEach() {
    import scala.collection.mutable.Buffer
    import scala.collection.JavaConverters._

    val deletes = for {
      i <- 1 to 10
      j <- 1 to 100
    } yield Seq(
        new Delete(InMsgKey(0, i, j)),
        new Delete(OutMsgKey(0, i, j)))

    client.delete(Buffer(deletes.flatten: _*).asJava)
  }

  override def beforeAll() {
    util = new HBaseTestingUtility(journalProps.configuration)
    util.startMiniCluster()

    admin = new HBaseAdmin(journalProps.configuration)
    admin.createTable(new HTableDescriptor(tableName))
    admin.disableTable(tableName)
    admin.addColumn(tableName, new HColumnDescriptor(columnFamilyName))
    admin.enableTable(tableName)

    client = new HTable(journalProps.configuration, tableName)
  }

  override def afterAll() = try {
    util.shutdownMiniCluster()
    util.cleanupTestDir()
  } catch { case _: Throwable => /* ignore */ }
}
