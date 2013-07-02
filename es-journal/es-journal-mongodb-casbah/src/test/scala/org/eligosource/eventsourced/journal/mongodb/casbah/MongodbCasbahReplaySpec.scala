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

import java.io.File

import com.mongodb.casbah.Imports._

import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.conf.Configuration

import org.scalatest.BeforeAndAfterEach

import org.eligosource.eventsourced.journal.common.PersistentReplaySpec

class MongodbCasbahReplaySpec extends PersistentReplaySpec with MongodbSpecSupport with BeforeAndAfterEach {
  val dbName = "es2"
  val collName = "event"

  val snapshotFileSystemRoot = "es-journal/es-journal-mongodb-casbah/target/journal"
  val snapshotFilesystem = FileSystem.getLocal(new Configuration)

  snapshotFilesystem.setWorkingDirectory(new Path(snapshotFileSystemRoot))

  // Since multiple embedded instances will run, each one must have a different port.
  override def mongoPort = 54322

  def journalProps = MongodbCasbahJournalProps(MongoClient(mongoLocalHostName, mongoPort), dbName, collName, snapshotFilesystem = snapshotFilesystem)

  override def afterEach() {
    MongoClient(mongoLocalHostName, mongoPort)(dbName)(collName).dropCollection()
    FileUtils.deleteDirectory(new File(snapshotFileSystemRoot))
  }
}
