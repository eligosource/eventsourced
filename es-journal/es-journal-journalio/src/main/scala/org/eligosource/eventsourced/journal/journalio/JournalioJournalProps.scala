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
package org.eligosource.eventsourced.journal.journalio

import java.io.File

import scala.concurrent.duration._

import akka.actor.Actor

import org.apache.hadoop.fs.{FileSystem, Path}

import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshottingProps
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting.defaultLocalFilesystem

/**
 * Configuration object for a [[https://github.com/sbtourist/Journal.IO Journal.IO]] based journal. It
 * has the following properties.
 *
 * Pros:
 *
 *  - efficient replay of input messages for all processors (batch replay with optional lower bound).
 *  - efficient replay of output messages (after initial replay of input messages)
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for a single processor requires full scan (with optional lower bound)
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import java.io.File
 *
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.journalio.JournalioJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journalDir: File = ...
 *  val journal: ActorRef = Journal(JournalioJournalProps(journalDir))
 * }}}

 * @param dir Journal directory.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 * @param fsync `true` if every write is physically synced. Default is `false`.
 * @param checksum `true` if checksums are verified on read. Default is `false`.
 */
case class JournalioJournalProps(
  dir: File,
  name: Option[String] = None,
  dispatcherName: Option[String] = None,
  fsync: Boolean = false,
  checksum: Boolean = false,
  snapshotDir: Path = new Path("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotLoadTimeout: FiniteDuration = 1 hour,
  snapshotSaveTimeout: FiniteDuration = 1 hour,
  snapshotFilesystem: FileSystem = defaultLocalFilesystem)
  extends JournalProps with HadoopFilesystemSnapshottingProps[JournalioJournalProps] {

  val snapshotPath =
    if (!snapshotDir.isAbsolute && snapshotFilesystem == defaultLocalFilesystem) {
      // default local file system and relative snapshot dir:
      // store snapshots relative to journal dir
      new Path(new Path(dir.toURI), snapshotDir)
    } else snapshotDir

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified journal actor name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified physical sync setting.
   */
  def withFsync(fsync: Boolean) =
    copy(fsync = fsync)

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified checksum verification setting.
   */
  def withChecksum(checksum: Boolean) =
    copy(checksum = checksum)

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified snapshot dir.
   */
  def withSnapshotDir(snapshotDir: Path) =
    copy(snapshotDir = snapshotDir)

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified snapshot serializer.
   */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) =
    copy(snapshotSerializer = snapshotSerializer)

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified snapshot load timeout.
   */
  def withSnapshotLoadTimeout(snapshotLoadTimeout: FiniteDuration) =
    copy(snapshotLoadTimeout = snapshotLoadTimeout)

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified snapshot save timeout.
   */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) =
    copy(snapshotSaveTimeout = snapshotSaveTimeout)

  /**
   * Java API.
   *
   * Returns a new `JournalioJournalProps` with specified snapshot filesystem.
   */
  def withSnapshotFilesystem(snapshotFilesystem: FileSystem) =
    copy(snapshotFilesystem = snapshotFilesystem)

  def createJournalActor: Actor =
    new JournalioJournal(this)
}

object JournalioJournalProps {
  /**
   * Java API.
   */
  def create(dir: File) =
    JournalioJournalProps(dir)
}