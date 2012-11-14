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
import org.eligosource.eventsourced.util._

/**
 * LevelDB based journal that organizes entries primarily based on sequence numbers,
 * keeping input and output entries separated.
 *
 * Pros:
 *
 *  - efficient replay of input messages for all processors (batch replay
 *    with optional lower bound).
 *  - efficient replay of output messages
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for a single processor requires full scan
 *    (with optional lower bound)
 */
private [eventsourced] class LeveldbJournalSS(dir: File) extends Journal {
  import LeveldbJournalSS._

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  val writeOutMsgCache = new WriteOutMsgCache[Long]

  def executeWriteInMsg(cmd: WriteInMsg) {
    val pmsg = cmd.message.clearConfirmationSettings
    val pcmd = writeInMsgSerializer.toBytes(cmd.copy(message = pmsg, target = null))
    leveldb.put(SSKey(In, counter, 0), pcmd)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg) = withBatch { batch =>
    val pmsg = cmd.message.clearConfirmationSettings
    val pcmd = writeOutMsgSerializer.toBytes(cmd.copy(message = pmsg, target = null))

    batch.put(SSKey(Out, counter, cmd.channelId), pcmd)

    if (cmd.ackSequenceNr != SkipAck) {
      batch.put(SSKey(In, cmd.ackSequenceNr, cmd.channelId), Array.empty[Byte])
    }

    writeOutMsgCache.update(cmd, pmsg.sequenceNr)
  }

  def executeWriteAck(cmd: WriteAck) {
    leveldb.put(SSKey(In, cmd.ackSequenceNr, cmd.channelId), Array.empty[Byte])
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    writeOutMsgCache.update(cmd).foreach { loc => leveldb.delete(SSKey(Out, loc, 0)) }
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    val starts = cmds.foldLeft(Map.empty[Int, (Long, ActorRef)]) { (a, r) =>
      a + (r.processorId -> (r.fromSequenceNr, r.target))
    }
    val start = if (starts.isEmpty) 0L else starts.values.map(_._1).min
    replay(In, start, writeInMsgSerializer) { (cmd, acks) =>
      starts.get(cmd.processorId) match {
        case Some((fromSequenceNr, target)) if (cmd.message.sequenceNr >= fromSequenceNr) => {
          p(cmd.message.copy(acks = acks), target)
        }
        case _ => {}
      }
    }
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    executeBatchReplayInMsgs(List(cmd), (msg, _) => p(msg))
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    writeOutMsgCache.messages(cmd.channelId, cmd.fromSequenceNr).foreach(p)
  }

  def storedCounter = leveldb.get(CounterKeyBytes, levelDbReadOptions) match {
    case null  => 0L
    case bytes => bytesToCounter(bytes)
  }

  override def start() {
    replay(Out, 0L, writeOutMsgSerializer) { (cmd, acks) =>
      writeOutMsgCache.update(cmd, cmd.message.sequenceNr)
    }
  }

  override def stop() {
    leveldb.close()
  }

  def replay[T](direction: Int, fromSequenceNr: Long, serializer: Serializer[T])(p: (T, List[Int]) => Unit): Unit = {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = SSKey(In, fromSequenceNr, 0)
      iter.seek(startKey)
      replay(iter, startKey, serializer)(p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def replay[T](iter: DBIterator, key: SSKey, serializer: Serializer[T])(p: (T, List[Int]) => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = bytesToKey(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey, serializer)(p)
      } else if (key.direction == nextKey.direction) {
        val cmd = serializer.fromBytes(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(cmd, channelIds)
        replay(iter, nextKey, serializer)(p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: DBIterator, key: SSKey, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = bytesToKey(nextEntry.getKey)
      if (key.direction  == nextKey.direction &&
          key.sequenceNr == nextKey.sequenceNr) {
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

private object LeveldbJournalSS {
  val In = 1
  val Out = 2

  case class SSKey(
    /** In or Out */
    direction: Int,
    sequenceNr: Long,
    confirmingChannelId: Int
  )

  // TODO: make configurable
  private val writeInMsgSerializer = new JavaSerializer[WriteInMsg]
  private val writeOutMsgSerializer = new JavaSerializer[WriteOutMsg]

  val keySerializer = new Serializer[SSKey] {
    def toBytes(key: SSKey): Array[Byte] = {
      val bb = ByteBuffer.allocate(20)
      bb.putInt(key.direction)
      bb.putLong(key.sequenceNr)
      bb.putInt(key.confirmingChannelId)
      bb.array
    }

    def fromBytes(bytes: Array[Byte]): SSKey = {
      val bb = ByteBuffer.wrap(bytes)
      val direction = bb.getInt
      val sequenceNumber = bb.getLong
      val confirmingChannelId = bb.getInt
      new SSKey(direction, sequenceNumber, confirmingChannelId)
    }
  }

  implicit def keyToBytes(key: SSKey): Array[Byte] = keySerializer.toBytes(key)
  implicit def bytesToKey(bytes: Array[Byte]): SSKey = keySerializer.fromBytes(bytes)

  val CounterKeyBytes = keyToBytes(SSKey(0, 0L, 0))
}