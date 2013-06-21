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

import scala.concurrent.duration._

import akka.actor.Actor

import com.mongodb.casbah.Imports._

import org.apache.hadoop.fs.{FileSystem, Path}

import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshottingProps
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting.defaultLocalFilesystem

/**
 * Configuration object for an Mongodb/Casbah based journal.
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.mongodb.casbah.MongodbCasbahJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journal: ActorRef = Journal(MongodbCasbahJournalProps(journalConn))
 * }}}
 *
 * @param mongoClient Required mongoDB/Casbah client.
 * @param dbName Required mongoDB database name.
 * @param collName Required mongoDB collection name.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 */
case class MongodbCasbahJournalProps(
  mongoClient: MongoClient,
  dbName: String,
  collName: String,
  snapshotPath: Path = new Path("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotLoadTimeout: FiniteDuration = 1 hour,
  snapshotSaveTimeout: FiniteDuration = 1 hour,
  snapshotFilesystem: FileSystem = defaultLocalFilesystem,
  name: Option[String] = None, dispatcherName: Option[String] = None)
  extends JournalProps with HadoopFilesystemSnapshottingProps[MongodbCasbahJournalProps] {

  /**
   * Java API.
   *
   * Returns a new `MongodbCasbahJournalProps` with specified journal actor name.
   */
  def withName(name: String) = copy(name = Some(name))

  /**
   * Java API.
   *
   * Returns a new `MongodbCasbahJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) = copy(dispatcherName = Some(dispatcherName))

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

  def createJournalActor: Actor = new MongodbCasbahJournal(this)
}

object MongodbCasbahJournalProps {
  /**
   * Java API.
   */
  def create(mongoClient: MongoClient, dbName: String, collName: String) =
    MongodbCasbahJournalProps(mongoClient, dbName, collName)
}