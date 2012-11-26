/*
 * Copyright 2012 Eligotech BV.
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
package org.eligosource.eventsourced.journal

import java.io.File
import java.nio.ByteBuffer

import akka.actor._

import org.fusesource.leveldbjni.JniDBFactory._
import org.iq80.leveldb._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._

/**
 * LevelDB based journal that organizes entries primarily based on processor id.
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
private [eventsourced] class LeveldbJournalPS(dir: File) extends Journal {
  import LeveldbJournalPS._

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  val serialization = Serialization(context.system)

  implicit def msgToBytes(msg: Message): Array[Byte] = serialization.serializeMessage(msg)
  implicit def msgFromBytes(bytes: Array[Byte]): Message = serialization.deserializeMessage(bytes)

  def executeWriteInMsg(cmd: WriteInMsg): Unit = withBatch { batch =>
    val msg = cmd.message
    batch.put(CounterKeyBytes, counterToBytes(counter))
    batch.put(Key(cmd.processorId, 0, msg.sequenceNr, 0), msg.clearConfirmationSettings)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg): Unit = withBatch { batch =>
    val msg = cmd.message
    batch.put(CounterKeyBytes, counterToBytes(counter))
    batch.put(Key(Int.MaxValue, cmd.channelId, msg.sequenceNr, 0), msg.clearConfirmationSettings)
    if (cmd.ackSequenceNr != SkipAck)
      batch.put(Key(cmd.ackProcessorId, 0, cmd.ackSequenceNr, cmd.channelId), Array.empty[Byte])
  }

  def executeWriteAck(cmd: WriteAck) {
    val k = Key(cmd.processorId, 0, cmd.ackSequenceNr, cmd.channelId)
    leveldb.put(k, Array.empty[Byte], levelDbWriteOptions)
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    val k = Key(Int.MaxValue, cmd.channelId, cmd.msgSequenceNr, 0)
    leveldb.delete(k, levelDbWriteOptions)
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => executeReplayInMsgs(cmd, msg => p(msg, cmd.target)))
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    replay(cmd.processorId, 0, cmd.fromSequenceNr, p)
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    replay(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr, p)
  }

  def storedCounter = leveldb.get(CounterKeyBytes, levelDbReadOptions) match {
    case null  => 0L
    case bytes => counterFromBytes(bytes)
  }

  override def stop() {
    leveldb.close()
  }

  private def replay(processorId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = Key(processorId, channelId, fromSequenceNr, 0)
      iter.seek(startKey)
      replay(iter, startKey, p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def replay(iter: DBIterator, key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey, p)
      } else if (key.processorId       == nextKey.processorId &&
        key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = serialization.deserializeMessage(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: DBIterator, key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (key.processorId       == nextKey.processorId &&
        key.initiatingChannelId == nextKey.initiatingChannelId &&
        key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }

  private def withBatch(p: WriteBatch => Unit) {
    val batch = leveldb.createWriteBatch()
    try {
      p(batch)
      leveldb.write(batch, levelDbWriteOptions)
    } finally {
      batch.close()
    }
  }
}

private object LeveldbJournalPS {
  implicit def keyToBytes(key: Key): Array[Byte] = {
    val bb = ByteBuffer.allocate(20)
    bb.putInt(key.processorId)
    bb.putInt(key.initiatingChannelId)
    bb.putLong(key.sequenceNr)
    bb.putInt(key.confirmingChannelId)
    bb.array
  }

  implicit def keyFromBytes(bytes: Array[Byte]): Key = {
    val bb = ByteBuffer.wrap(bytes)
    val processorId = bb.getInt
    val initiatingChannelId = bb.getInt
    val sequenceNumber = bb.getLong
    val confirmingChannelId = bb.getInt
    new Key(processorId, initiatingChannelId, sequenceNumber, confirmingChannelId)
  }

  val CounterKeyBytes = keyToBytes(Key(0, 0, 0L, 0))
}
