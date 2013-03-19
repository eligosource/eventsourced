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

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client._

/**
 * Creates an HBase event message table.
 */
object CreateSchema extends App {
  apply(HBaseConfiguration.create(), 4)

  /**
   * Creates an HBase event message table.
   *
   * @param c HBase configuration object.
   * @param numPartitions number of partitions to create.
   */
  def apply(c: Configuration, numPartitions: Int) {
    val admin = new HBaseAdmin(c)

    admin.createTable(new HTableDescriptor(TableName), Keysplit(numPartitions))
    admin.disableTable(TableName)
    admin.addColumn(TableName, new HColumnDescriptor(ColumnFamilyName))
    admin.enableTable(TableName)
    admin.close()
  }
}

/**
 * Drops an existing HBase event message table.
 */
object DropSchema extends App {
  val config = HBaseConfiguration.create()
  val admin = new HBaseAdmin(config)

  admin.disableTable(TableName)
  admin.deleteTable(TableName)
  admin.close()
}

private [hbase] object Keysplit {
  val bo = Ordering.by((x: Array[Byte]) => x.toIterable)

  def apply(numPartitions: Int): Array[Array[Byte]] =
    (1 until numPartitions).map(p => InMsgKey(p, 0, 0L).toBytes).toArray

  def partition(key: Key, splits: Array[Array[Byte]]): Int = {
    val idx = (splits.indexWhere(s => bo.lt(key.toBytes, s)))
    if (idx == -1) splits.length else idx
  }
}
