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

import java.io._

import akka.actor.Actor

import org.eligosource.eventsourced.journal.common.serialization._

private [journalio] trait JournalioSnapshotting extends LocalFilesystemSnapshotting { this: Actor =>
  def props: JournalioJournalProps
  def snapshotSerializer = props.snapshotSerializer
  def snapshotSaveTimeout = props.snapshotSaveTimeout

  val snapshotDir: File =
    if (props.snapshotDir.isAbsolute) props.snapshotDir
    else new File(props.dir, props.snapshotDir.getPath)
}
