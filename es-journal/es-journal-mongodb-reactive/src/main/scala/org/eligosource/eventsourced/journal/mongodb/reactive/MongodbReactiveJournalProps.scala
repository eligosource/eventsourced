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

import akka.actor.Actor

import org.apache.hadoop.fs.{FileSystem, Path}

import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshottingProps
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting.defaultLocalFilesystem

import scala.concurrent.duration._

import reactivemongo.core.actors.Authenticate
import reactivemongo.core.commands.GetLastError

/**
 * Configuration object for an Mongodb/Reactive based journal.
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.mongodb.reactive.MongodbReactiveJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journal: ActorRef = Journal(MongodbReactiveJournalProps(List("localhost:27017"))
 * }}}
 *
 * @param nodes Required nodes to connect to.
 * @param authentications Optional authentication list. Defaults to List.empty.
 * @param nbChannelsPerNode Optional number of channels to open per node. Defaults to 10.
 * @param mongoDBSystemName Optional name of the newly created {@see MongoDBSystem} actor, if needed
 * @param dbName Optional mongoDB database name. Defaults to {@see DefaultDatabaseName}
 * @param collName Optional mongoDB collection name. Defaults to {@see DefaultCollectionName}
 * @param writeConcern Optional GetLastError. Defaults to awaitJournalCommit = false, waitForReplicatedOn = None, fsync = false.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 * @param initTimeout Timeout for journal initialization. During initialization the highest stored sequence number is loaded from the event message table.
 * @param replayChunkSize Maximum number of event messages to keep in memory during replay.
 */


case class MongodbReactiveJournalProps(
  nodes: List[String],
  authentications: List[Authenticate] = List.empty,
  nbChannelsPerNode: Int = 10,
  mongoDBSystemName: Option[String] = None,
  dbName: String = DefaultDatabaseName,
  collName: String = DefaultCollectionName,
  writeConcern: GetLastError = GetLastError(awaitJournalCommit = false, waitForReplicatedOn = None, fsync = false),
  name: Option[String] = None,
  dispatcherName: Option[String] = None,
  initTimeout: FiniteDuration = 30 seconds,
  replayChunkSize: Int = 16 * 100,
  snapshotPath: Path = new Path("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotLoadTimeout: FiniteDuration = 1 hour,
  snapshotSaveTimeout: FiniteDuration = 1 hour,
  snapshotFilesystem: FileSystem = defaultLocalFilesystem)
    extends JournalProps with HadoopFilesystemSnapshottingProps[MongodbReactiveJournalProps] {

  /** Returns a new `MongodbReactiveJournalProps` with specified list of authentications. */
  def withAuthentications(authentications: List[Authenticate]) = copy(authentications = authentications)

  /** Returns a new `MongodbReactiveJournalProps` with specified number of channels per node */
  def withNbChannelsPerNode(nbChannelsPerNode: Int) = copy(nbChannelsPerNode = nbChannelsPerNode)

  /** Returns a new `MongodbReactiveJournalProps` with specified journal actor name. */
  def withMongoDBSystemName(mongoDBSystemName: String) = copy(mongoDBSystemName = Some(mongoDBSystemName))

  /** Returns a new `MongodbReactiveJournalProps` with specified database name. */
  def withDbName(dbName: String) = copy(dbName = dbName)

  /** Returns a new `MongodbReactiveJournalProps` with specified collection name. */
  def withCollName(collName: String) = copy(collName = collName)

  /** Returns a new `MongodbReactiveJournalProps` with specified journal actor name. */
  def withName(name: String) = copy(name = Some(name))

  /** Returns a new `MongodbReactiveJournalProps` with specified journal actor dispatcher name. */
  def withDispatcherName(dispatcherName: String) = copy(dispatcherName = Some(dispatcherName))

  /** Returns a new `MongodbReactiveJournalProps` with specified init timeout. */
  def withInitTimeout(initTimeout: FiniteDuration) = copy(initTimeout = initTimeout)

  /** Returns a new `MongodbReactiveJournalProps` with specified replay chunk size. */
  def withReplayChunkSize(replayChunkSize: Int) = copy(replayChunkSize = replayChunkSize)

  /** Java API. */
  def withSnapshotPath(snapshotPath: Path) = copy(snapshotPath = snapshotPath)

  /** Java API. */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) = copy(snapshotSerializer = snapshotSerializer)

  /** Java API. */
  def withSnapshotLoadTimeout(snapshotLoadTimeout: FiniteDuration) = copy(snapshotLoadTimeout = snapshotLoadTimeout)

  /** Java API. */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) = copy(snapshotSaveTimeout = snapshotSaveTimeout)

  /** Java API. */
  def withSnapshotFilesystem(snapshotFilesystem: FileSystem) = copy(snapshotFilesystem = snapshotFilesystem)

  /** Returns a new `MongodbRactiveJournal`. */
  def createJournalActor: Actor = new MongodbReactiveJournal(this)
}

/** Companion object **/
object MongodbReactiveJournalProps {
  def create(nodes: List[String]) = MongodbReactiveJournalProps(nodes)
}
