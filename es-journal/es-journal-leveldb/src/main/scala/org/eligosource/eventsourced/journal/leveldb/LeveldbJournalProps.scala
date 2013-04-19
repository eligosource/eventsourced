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
package org.eligosource.eventsourced.journal.leveldb

import java.io.File

import scala.concurrent.duration._

import akka.actor.Actor

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common._

/**
 * Configuration object for a [[http://code.google.com/p/leveldb/ LevelDB]] based journal. This
 * journal comes with different optimizations to choose from, as described at the methods
 *
 *  - `withProcessorStructure`
 *  - `withSequenceStructure`
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import java.io.File
 *
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journalDir: File = ...
 *  val journal: ActorRef = Journal(LeveldbJournalProps(journalDir))
 * }}}
 *
 * @param dir Journal directory.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 * @param fsync `true` if every write is physically synced. Default is `false`.
 * @param checksum `true` if checksums are verified on read. Default is `false`.
 * @param processorStructured `true` if entries are primarily ordered by processor
 *        id, `false` if entries are ordered by sequence number.  Default is `true`.
 * @param snapshotDir Directory where the journal will store snapshots. A relative
 *        path is relative to `dir`.
 * @param snapshotSerializer serializer for writing and reading snapshots.
 * @param snapshotSaveTimeout Timeout for saving a snapshot.
 */
case class LeveldbJournalProps(
  dir: File,
  name: Option[String] = None,
  dispatcherName: Option[String] = None,
  fsync: Boolean = false,
  checksum: Boolean = false,
  processorStructured: Boolean = true,
  snapshotDir: File = new File("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotSaveTimeout: FiniteDuration = 1 hour) extends JournalProps {

  /**
   *  Returns `false` if entries are primarily ordered by processor id,
   *  `true` if entries are ordered by sequence number. Default is `false`.
   */
  def sequenceStructured: Boolean =
    !processorStructured

  /**
   * Returns a new `LeveldbJournalProps` with specified journal actor name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `LeveldbJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  /**
   * Returns a new `LeveldbJournalProps` with specified physical sync setting.
   */
  def withFsync(fsync: Boolean) =
    copy(fsync = fsync)

  /**
   * Returns a new `LeveldbJournalProps` with specified checksum verification setting.
   */
  def withChecksum(checksum: Boolean) =
    copy(checksum = checksum)

  /**
   * Returns a new `LeveldbJournalProps` with `processorStructured` set to `true` and
   * `sequenceStructured` set to `false`. With this setting, entries will be primarily
   * ordered by processor id.
   *
   * Pros:
   *
   *  - efficient replay of input messages for all processors (batch replay)
   *  - efficient replay of input messages for a single processor
   *  - efficient replay of output messages
   *
   * Cons:
   *
   *  - deletion of old entries requires full scan
   */
  def withProcessorStructure =
    copy(processorStructured = true)

  /**
   * Returns a new `LeveldbJournalProps` with `processorStructured` set to `false` and
   * `sequenceStructured` set to `true`. With this setting, entries will be ordered by
   * sequence number.
   *
   * Pros:
   *
   *  - efficient replay of input messages for all processors (batch replay with optional lower bound)
   *  - efficient replay of output messages
   *  - efficient deletion of old entries
   *
   * Cons:
   *
   *  - replay of input messages for a single processor requires full scan (with optional lower bound)
   */
  def withSequenceStructure =
    copy(processorStructured = false)

  /**
   * Returns a new `LeveldbJournalProps` with specified snapshot directory.
   */
  def withSnapshotDir(snapshotDir: File) =
    copy(snapshotDir = snapshotDir)

  /**
   * Returns a new `LeveldbJournalProps` with specified snapshot serializer.
   */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) =
    copy(snapshotSerializer = snapshotSerializer)

  /**
   * Returns a new `LeveldbJournalProps` with specified snapshot save timeout.
   */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) =
    copy(snapshotSaveTimeout = snapshotSaveTimeout)

  def journal: Actor = {
    if (processorStructured) {
      new LeveldbJournalPS(this)
    } else {
      new LeveldbJournalSS(this)
    }
  }
}

