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

import java.nio.ByteBuffer

import akka.actor._

import org.iq80.leveldb._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting
import org.eligosource.eventsourced.journal.common.support.SynchronousWriteReplaySupport
import org.eligosource.eventsourced.journal.common.util._

/**
 * [[http://code.google.com/p/leveldb/ LevelDB]] based journal that orders entries
 * by sequence number, keeping input and output entries separated.
 *
 * Pros:
 *
 *  - efficient replay of input messages for all processors (batch replay with optional lower bound).
 *  - efficient replay of output messages
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for a single processor requires full scan (with optional lower bound)
 */
private [eventsourced] class LeveldbJournalSS(val props: LeveldbJournalProps) extends SynchronousWriteReplaySupport
    with LeveldbJournal
    with HadoopFilesystemSnapshotting {

  import LeveldbJournalSS._
  import JournalProtocol._

  val writeOutMsgCache = new WriteOutMsgCache[Long]

  implicit def cmdToBytes(cmd: AnyRef): Array[Byte] = serialization.serializeCommand(cmd)
  implicit def cmdFromBytes[A](bytes: Array[Byte]): A = serialization.deserializeCommand(bytes).asInstanceOf[A]

  def executeWriteInMsg(cmd: WriteInMsg) = withBatch { batch =>
    val pmsg = cmd.message.clearConfirmationSettings
    val pcmd = cmdToBytes(cmd.copy(message = pmsg, target = null))

    batch.put(CounterKeyBytes, counterToBytes(counter))
    batch.put(SSKey(In, counter, 0), pcmd)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg) = withBatch { batch =>
    val pmsg = cmd.message.clearConfirmationSettings
    val pcmd = cmdToBytes(cmd.copy(message = pmsg, target = null))

    batch.put(CounterKeyBytes, counterToBytes(counter))
    batch.put(SSKey(Out, counter, 0), pcmd)

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
    val ranges = cmds.foldLeft(Map.empty[Int, (Long, Long, ActorRef)]) { (a, r) =>
      a + (r.processorId -> (r.fromSequenceNr, r.toSequenceNr, r.target))
    }
    val start = if (ranges.isEmpty) 0L else ranges.values.map(_._1).min
    val stop = if (ranges.isEmpty) Long.MaxValue else ranges.values.map(_._2).max

    replay(In, start, stop, cmdFromBytes[WriteInMsg] _) { (cmd, acks) =>
      ranges.get(cmd.processorId) match {
        case Some((fromSequenceNr, toSequenceNr, target))
          if (cmd.message.sequenceNr >= fromSequenceNr &&
              cmd.message.sequenceNr <= toSequenceNr) => {
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
    case bytes => counterFromBytes(bytes)
  }

  override def start() {
    initSnapshotting()
    replay(Out, 0L, Long.MaxValue, cmdFromBytes[WriteOutMsg] _) { (cmd, acks) =>
      writeOutMsgCache.update(cmd, cmd.message.sequenceNr)
    }
  }

  override def stop() {
    leveldb.close()
  }

  def replay[T](direction: Int, fromSequenceNr: Long, toSequenceNr: Long, deserializer: Array[Byte] => T)(p: (T, List[Int]) => Unit) {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = SSKey(direction, fromSequenceNr, 0)
      iter.seek(startKey)
      replay(iter, startKey, toSequenceNr, deserializer)(p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def replay[T](iter: DBIterator, key: SSKey, toSequenceNr: Long, deserializer: Array[Byte] => T)(p: (T, List[Int]) => Unit) {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.sequenceNr > toSequenceNr) {
        // end iteration here
      } else if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey, toSequenceNr, deserializer)(p)
      } else if (key.direction == nextKey.direction) {
        val cmd = deserializer(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(cmd, channelIds)
        replay(iter, nextKey, toSequenceNr, deserializer)(p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: DBIterator, key: SSKey, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
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

  implicit def keyToBytes(key: SSKey): Array[Byte] = {
    val bb = ByteBuffer.allocate(20)
    bb.putInt(key.direction)
    bb.putLong(key.sequenceNr)
    bb.putInt(key.confirmingChannelId)
    bb.array
  }

  implicit def keyFromBytes(bytes: Array[Byte]): SSKey = {
    val bb = ByteBuffer.wrap(bytes)
    val direction = bb.getInt
    val sequenceNumber = bb.getLong
    val confirmingChannelId = bb.getInt
    new SSKey(direction, sequenceNumber, confirmingChannelId)
  }

  val CounterKeyBytes = keyToBytes(SSKey(0, 0L, 0))
}
