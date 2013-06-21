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

import org.apache.hadoop.fs.{FileSystem, Path}

import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshottingProps
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting.defaultLocalFilesystem


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
 * @param zookeeperQuorum Comma separated list of servers in the ZooKeeper quorum.
 *        See also the `hbase.zookeeper.quorum`
 *        [[http://hbase.apache.org/book/config.files.html configuration]] property.
 * @param tableName Event message table name.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 * @param replayChunkSize Maximum number of event messages to keep in memory during replay.
 * @param initTimeout Timeout for journal initialization. During initialization
 *        the highest stored sequence number is loaded from the event message table.
 */
case class HBaseJournalProps(
  zookeeperQuorum: String,
  tableName: String = DefaultTableName,
  name: Option[String] = None,
  dispatcherName: Option[String] = None,
  replayChunkSize: Int = 16 * 100,
  initTimeout: FiniteDuration = 30 seconds,
  snapshotPath: Path = new Path("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotLoadTimeout: FiniteDuration = 1 hour,
  snapshotSaveTimeout: FiniteDuration = 1 hour,
  snapshotFilesystem: FileSystem = defaultLocalFilesystem)
  extends JournalProps with HadoopFilesystemSnapshottingProps[HBaseJournalProps] {

  /**
   * Java API.
   */
  def withTableName(tableName: String) =
    copy(tableName = tableName)

  /**
   * Java API.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Java API.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  /**
   * Java API.
   */
  def withReplayChunkSize(replayChunkSize: Int) =
    copy(replayChunkSize = replayChunkSize)

  /**
   * Java API.
   */
  def withInitTimeout(initTimeout: FiniteDuration) =
    copy(initTimeout = initTimeout)

  /**
   * Java API.
   */
  def withSnapshotPath(snapshotPath: Path) =
    copy(snapshotPath = snapshotPath)

  /**
   * Java API.
   */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) =
    copy(snapshotSerializer = snapshotSerializer)

  /**
   * Java API.
   */
  def withSnapshotLoadTimeout(snapshotLoadTimeout: FiniteDuration) =
    copy(snapshotLoadTimeout = snapshotLoadTimeout)

  /**
   * Java API.
   */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) =
    copy(snapshotSaveTimeout = snapshotSaveTimeout)

  /**
   * Java API.
   */
  def withSnapshotFilesystem(snapshotFilesystem: FileSystem) =
    copy(snapshotFilesystem = snapshotFilesystem)

  def createJournalActor: Actor =
    new HBaseJournal(this)
}

object HBaseJournalProps {
  /**
   * Java API.
   */
  def create(zookeeperQuorum: String) =
    HBaseJournalProps(zookeeperQuorum)
}