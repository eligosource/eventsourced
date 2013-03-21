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
import org.apache.hadoop.hbase.util._

/**
 * Creates an HBase event message table.
 *
 * Usage from the sbt command prompt:
 *
 * {{{
 *   > project eventsourced-journal-hbase
 *   > run-main org.eligosource.eventsourced.journal.hbase.CreateTable <zookeeperQuorum> [<tableName>] [<partitionCount>]
 * }}}
 *
 * Usage from within an application:
 *
 * {{{
 *  import org.eligosource.eventsourced.journal.hbase.CreateTable
 *
 *  class Example {
 *    val zookeeperQuorum = "localhost:2181" // comma separated list of servers in the ZooKeeper quorum
 *    val tableName       = "event"          // name of the event message table to be created
 *    val partitionCount  = 16               // number of regions the event message table is pre-split
 *
 *    CreateTable(zookeeperQuorum, tableName, partitionCount)
 *  }
 * }}}
 *
 * Alternatively, applications may also use an HBase configuration object instead of `zookeeperQuorum`:
 *
 * {{{
 *  import org.apache.hadoop.conf.Configuration
 *  import org.eligosource.eventsourced.journal.hbase.CreateTable
 *
 *  class Example {
 *    val config: Configuration = ...       // HBase configuration object
 *    val tableName             = "event"   // name of the event message table to be created
 *    val partitionCount        = 16        // number of regions the event message table is pre-split
 *
 *    CreateTable(config, tableName, partitionCount)
 *  }
 * }}}
 *
 */
object CreateTable extends App {
  if (args.length < 1) {
    println("usage: CreateTable <zookeeperQuorum> [<tableName>] [<partitionCount>]")
  } else {
    val tn = if (args.length > 1) args(1) else DefaultTableName
    val pc = if (args.length > 2) args(2).toInt else DefaultPartitionCount
    apply(args(0), tn, pc)
  }

  /**
   * Creates an HBase event message table.
   *
   * @param zookeeperQuorum Comma separated list of servers in the ZooKeeper quorum.
   *        See also the `hbase.zookeeper.quorum`
   *        [[http://hbase.apache.org/book/config.files.html configuration]] property.
   * @param tableName name of the event message table to be created.
   * @param partitionCount number of regions the event message table is pre-split.
   */
  def apply(zookeeperQuorum: String, tableName: String, partitionCount: Int) {
    val config = HBaseConfiguration.create()
    config.set("hbase.zookeeper.quorum", zookeeperQuorum)
    apply(config, tableName, partitionCount)
  }

  /**
   * Creates an HBase event message table.
   *
   * @param config HBase configuration object.
   * @param tableName name of the event message table to be created.
   * @param partitionCount number of regions the event message table is pre-split.
   */
  def apply(config: Configuration, tableName: String = DefaultTableName, partitionCount: Int = DefaultPartitionCount) {
    val admin = new HBaseAdmin(config)
    admin.createTable(new HTableDescriptor(tableName), Keysplit(partitionCount))
    admin.disableTable(tableName)
    admin.addColumn(tableName, new HColumnDescriptor(ColumnFamilyName))
    admin.enableTable(tableName)
    admin.close()

    val put = new Put(PartitionCountKey().toBytes)
    put.add(ColumnFamilyNameBytes, PartitionCountColumnNameBytes, Bytes.toBytes(partitionCount))

    val client = new HTable(config, tableName)
    client.put(put)
    client.close()
  }
}

/**
 * Deletes an HBase event message table.
 *
 * Usage from the sbt command prompt:
 *
 * {{{
 *   > project eventsourced-journal-hbase
 *   > run-main org.eligosource.eventsourced.journal.hbase.DeleteTable <zookeeperQuorum> [<tableName>]
 * }}}
 *
 * Usage from within an application:
 *
 * {{{
 *  import org.eligosource.eventsourced.journal.hbase.DeleteTable
 *
 *  class Example {
 *    val zookeeperQuorum = "localhost:2181" // comma separated list of servers in the ZooKeeper quorum
 *    val tableName       = "event"          // name of the event message table to be deleted
 *
 *    DeleteTable(zookeeperQuorum, tableName)
 *  }
 * }}}
 */
object DeleteTable extends App {
  if (args.length < 1) {
    println("usage: CreateTable <zookeeperQuorum> [<tableName>]")
  } else {
    val tn = if (args.length > 1) args(1) else DefaultTableName
    apply(args(0), tn)
  }

  /**
   * Deletes an HBase event message table.
   *
   * @param zookeeperQuorum Comma separated list of servers in the ZooKeeper quorum.
   *        See also the `hbase.zookeeper.quorum`
   *        [[http://hbase.apache.org/book/config.files.html configuration]] property.
   * @param tableName name of the event message table to be deleted.
   */
  def apply(zookeeperQuorum: String, tableName: String = DefaultTableName) {
    val config = HBaseConfiguration.create()
    config.set("hbase.zookeeper.quorum", zookeeperQuorum)

    val admin = new HBaseAdmin(config)
    admin.disableTable(tableName)
    admin.deleteTable(tableName)
    admin.close()
  }
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
