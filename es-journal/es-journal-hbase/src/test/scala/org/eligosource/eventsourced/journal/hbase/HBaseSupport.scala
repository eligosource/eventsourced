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

object HBaseSupport {
  val config = HBaseConfiguration.create()
  val client = new HTable(config, "event")
}

trait HBaseSupport {
  import HBaseSupport._

  val journalProps = HBaseJournalProps(config)

  def cleanup() {
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
}

object CreateSchema extends App {
  val config = HBaseConfiguration.create()
  val admin = new HBaseAdmin(config)

  admin.createTable(new HTableDescriptor(tableName))
  admin.disableTable(tableName)
  admin.addColumn(tableName, new HColumnDescriptor(columnFamilyName))
  admin.enableTable(tableName)
  admin.close()
}

object DropSchema extends App {
  val config = HBaseConfiguration.create()
  val admin = new HBaseAdmin(config)

  admin.disableTable(tableName)
  admin.deleteTable(tableName)
  admin.close()
}

