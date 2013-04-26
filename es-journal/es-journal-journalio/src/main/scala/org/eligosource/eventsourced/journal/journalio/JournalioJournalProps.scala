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

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer

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
  snapshotDir: File = new File("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotSaveTimeout: FiniteDuration = 1 hour) extends JournalProps {

  /**
   * Returns a new `JournalioJournalProps` with specified journal actor name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `JournalioJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  /**
   * Returns a new `JournalioJournalProps` with specified physical sync setting.
   */
  def withFsync(fsync: Boolean) =
    copy(fsync = fsync)

  /**
   * Returns a new `JournalioJournalProps` with specified checksum verification setting.
   */
  def withChecksum(checksum: Boolean) =
    copy(checksum = checksum)

  /**
   * Returns a new `JournalioJournalProps` with specified snapshot directory.
   */
  def withSnapshotDir(snapshotDir: File) =
    copy(snapshotDir = snapshotDir)

  /**
   * Returns a new `JournalioJournalProps` with specified snapshot serializer.
   */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) =
    copy(snapshotSerializer = snapshotSerializer)

  /**
   * Returns a new `JournalioJournalProps` with specified snapshot save timeout.
   */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) =
    copy(snapshotSaveTimeout = snapshotSaveTimeout)

  def journal: Actor =
    new JournalioJournal(this)
}
