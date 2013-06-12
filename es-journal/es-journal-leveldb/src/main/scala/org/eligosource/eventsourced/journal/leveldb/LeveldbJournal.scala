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

import akka.actor._

import org.iq80.leveldb._

import org.eligosource.eventsourced.journal.common.serialization.CommandSerialization

trait LeveldbJournal { this: Actor =>
  def props: LeveldbJournalProps

  val levelDbReadOptions = new ReadOptions().verifyChecksums(props.checksum)
  val levelDbWriteOptions = new WriteOptions().sync(props.fsync)
  val leveldb = factory.open(props.dir, leveldbOptions)

  val serialization = CommandSerialization(context.system)

  def factory = {
    if (props.native) org.fusesource.leveldbjni.JniDBFactory.factory
    else org.iq80.leveldb.impl.Iq80DBFactory.factory
  }

  def leveldbOptions = {
    val options = new Options().createIfMissing(true)
    if (props.native) options else options.compressionType(CompressionType.NONE)
  }
}
