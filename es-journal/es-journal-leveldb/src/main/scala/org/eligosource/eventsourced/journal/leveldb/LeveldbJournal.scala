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

import akka.actor._

import org.eligosource.eventsourced.core.Journal

/**
 * @see [[org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps]]
 */
object LeveldbJournal {
  @deprecated("use Journal(LeveldbJournalProps(dir)) instead", "0.5")
  def processorStructured(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    Journal(LeveldbJournalProps(dir, name, dispatcherName))

  @deprecated("use Journal(LeveldbJournalProps(dir).withSequenceStructure) instead", "0.5")
  def sequenceStructured(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    Journal(LeveldbJournalProps(dir, name, dispatcherName).withSequenceStructure)

  @deprecated("use Journal(LeveldbJournalProps(dir)) instead", "0.5")
  def apply(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem) =
    processorStructured(dir, name = name, dispatcherName = dispatcherName)
}
