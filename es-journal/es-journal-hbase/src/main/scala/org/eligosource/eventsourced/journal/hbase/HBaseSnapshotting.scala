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

import akka.actor.Actor

import org.eligosource.eventsourced.journal.hbase.serialization.HadoopFilesystemSnapshotting

import org.apache.hadoop.fs.Path

private [hbase] trait HBaseSnapshotting extends HadoopFilesystemSnapshotting { this: Actor =>
  def props: HBaseJournalProps

  def snapshotFilesystem = props.snapshotFilesystem
  def snapshotSerializer = props.snapshotSerializer
  def snapshotSaveTimeout = props.snapshotLoadTimeout
  def snapshotLoadTimeout = props.snapshotSaveTimeout

  val snapshotDir =
    if (props.snapshotDir.isAbsolute) props.snapshotDir
    else new Path(snapshotFilesystem.getWorkingDirectory, props.snapshotDir)
}
