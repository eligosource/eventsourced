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

import scala.concurrent.duration._

import akka.actor.Actor

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.eligosource.eventsourced.core.JournalProps
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer

/**
 * Configuration object for a [[http://hbase.apache.org/ HBase]] backed journal. Journal
 * actors can be created from a configuration object as follows:
 *
 * {{{
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.hbase.HBaseJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val zookeeperQuorum: String = ... // "localhost", for example
 *  val tableName: String = ...       // "event", for example
 *  val journal: ActorRef = Journal(HBaseJournalProps(zookeeperQuorum))
 * }}}
 *
 * For storing event messages to HBase, an event message table must be initially created. See
 * [[org.eligosource.eventsourced.journal.hbase.CreateTable]] and
 * [[org.eligosource.eventsourced.journal.hbase.DeleteTable]] for details.
 *
 * Event messages will be evenly distributed (partitioned) across table regions.
 *
 * The HBase journal uses a Hadoop
 * [[http://hadoop.apache.org/docs/r1.1.2/api/org/apache/hadoop/fs/FileSystem.html `FileSystem`]]
 * instance for saving snapshots. By default, snapshots are saved to the local filesystem.
 * Applications may also provide other `FileSystem` instances (e.g. for saving snapshots to
 * HDFS), as shown in the following example:
 *
 * {{{
 *  ...
 *  import org.apache.hadoop.fs.FileSystem
 *
 *  ...
 *  val hdfs: FileSystem = FileSystem.get(...)
 *  val journal: ActorRef = Journal(HBaseJournalProps(..., snapshotFilesystem = hdfs))
 * }}}
 *
 * @param zookeeperQuorum Comma separated list of servers in the ZooKeeper quorum.
 *        See also the `hbase.zookeeper.quorum`
 *        [[http://hbase.apache.org/book/config.files.html configuration]] property.
 * @param tableName Event message table name.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 * @param replayChunkSize Maximum number of event messages to keep in memory during replay.
 * @param initTimeout Timeout for journal initialization. During initialization
 *        the highest stored sequence number is loaded from the event message table.
 * @param snapshotDir Path of directory where snapshots are stored on `snapshotFilesystem`.
 *        A relative path is relative to `snapshotFilesystem`'s working directory.
 * @param snapshotSerializer Serializer for writing and reading snapshots.
 * @param snapshotLoadTimeout Timeout for loading a snapshot.
 * @param snapshotSaveTimeout Timeout for saving a snapshot.
 * @param snapshotFilesystem Hadoop filesystem for storing snapshots. Defaults to the local
 *        file system. Should be set to [[org.apache.hadoop.hdfs.DistributedFileSystem]] in
 *        production.
 */
case class HBaseJournalProps(
  zookeeperQuorum: String,
  tableName: String = DefaultTableName,
  name: Option[String] = None,
  dispatcherName: Option[String] = None,
  replayChunkSize: Int = 16 * 100,
  initTimeout: FiniteDuration = 30 seconds,
  snapshotDir: Path = new Path("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotLoadTimeout: FiniteDuration = 1 hour,
  snapshotSaveTimeout: FiniteDuration = 1 hour,
  snapshotFilesystem: FileSystem = FileSystem.getLocal(new Configuration)) extends JournalProps {

  def withTableName(tableName: String) =
    copy(tableName = tableName)

  def withName(name: String) =
    copy(name = Some(name))

  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  def withReplayChunkSize(replayChunkSize: Int) =
    copy(replayChunkSize = replayChunkSize)

  def withInitTimeout(initTimeout: FiniteDuration) =
    copy(initTimeout = initTimeout)

  def withSnapshotDir(snapshotDir: Path) =
    copy(snapshotDir = snapshotDir)

  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) =
    copy(snapshotSerializer = snapshotSerializer)

  def withSnapshotLoadTimeout(snapshotLoadTimeout: FiniteDuration) =
    copy(snapshotLoadTimeout = snapshotLoadTimeout)

  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) =
    copy(snapshotSaveTimeout = snapshotSaveTimeout)

  def withSnapshotFilesystem(snapshotFilesystem: FileSystem) =
    copy(snapshotFilesystem = snapshotFilesystem)

  def journal: Actor =
    new HBaseJournal(this)
}
