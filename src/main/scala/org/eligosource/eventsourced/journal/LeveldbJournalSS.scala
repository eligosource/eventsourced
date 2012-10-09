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
import org.eligosource.eventsourced.util._

/**
 * LevelDB based journal that organizes entries primarily based on sequence numbers,
 * keeping input and output entries separated.
 *
 * Pros:
 *
 *  - efficient replay of input messages for composites i.e. single scan
 *    (with optional lower bound) for n processors.
 *  - efficient replay of output messages
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for individual processors requires full scan
 *    (with optional lower bound)
 */
private [eventsourced] class LeveldbJournalSS(dir: File) extends Actor {
  import LeveldbJournalSS._

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  val writeOutMsgCache = new WriteOutMsgCache[Long]

  var commandListener: Option[ActorRef] = None
  var counter = 0L

  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written

      val ck = SSKey(In, m.sequenceNr, 0)
      val sc = writeInMsgSerializer.toBytes(c.copy(message = m, target = null))
      leveldb.put(ck, sc)

      if (c.target != context.system.deadLetters) c.target ! c.message
      if (sender   != context.system.deadLetters) sender ! Ack

      counter = m.sequenceNr + 1
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteOutMsg => withBatch { batch =>
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written

      writeOutMsgCache.update(c, m.sequenceNr)

      val ck = SSKey(Out, m.sequenceNr, cmd.channelId)
      val sc = writeOutMsgSerializer.toBytes(c.copy(message = m, target = null))
      batch.put(ck, sc)
      if (c.ackSequenceNr != SkipAck) {
        val ak = SSKey(In, c.ackSequenceNr, c.channelId)
        batch.put(ak, Array.empty[Byte])
      }

      if (c.target != context.system.deadLetters) c.target ! c.message
      if (sender   != context.system.deadLetters) sender ! Ack

      counter = m.sequenceNr + 1
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteAck => {
      leveldb.put(SSKey(In, cmd.ackSequenceNr, cmd.channelId), Array.empty[Byte])
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteOutMsg => {
      writeOutMsgCache.update(cmd).foreach { loc => leveldb.delete(SSKey(Out, loc, 0)) }
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case lt: LoopThrough => {
      lt.target.!(lt)(sender)
    }
    case BatchDeliverOutMsgs(channels) => {
      channels.foreach(_ ! Deliver)
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case BatchReplayInMsgs(replays) => {
      val starts = replays.foldLeft(Map.empty[Int, (Long, ActorRef)]) { (a, r) =>
        a + (r.processorId -> (r.fromSequenceNr, r.target))
      }
      replay(In, starts.values.map(_._1).min, writeInMsgSerializer) { (cmd, acks) =>
        starts.get(cmd.processorId) match {
          case Some((fromSequenceNr, target)) if (cmd.message.sequenceNr >= fromSequenceNr) => {
            target ! cmd.message.copy(sender = None, acks = acks)
          }
          case _ => {}
        }
      }
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case r: ReplayInMsgs => {
      // call receive directly instead sending to self
      // (replay must not interleave with other commands)
      receive(BatchReplayInMsgs(List(r)))
    }
    case ReplayOutMsgs(chanId, fromNr, target) => {
      writeOutMsgCache.messages(chanId, fromNr).foreach(msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  override def preStart() {
    counter = getCounter
    replay(Out, 0L, writeOutMsgSerializer) { (cmd, acks) =>
      writeOutMsgCache.update(cmd, cmd.message.sequenceNr)
    }
  }

  override def postStop() {
    leveldb.close()
  }

  def getCounter = leveldb.get(CounterKeyBytes, levelDbReadOptions) match {
    case null  => 1L
    case bytes => bytesToCounter(bytes) + 1L
  }

  def withBatch(p: WriteBatch => Unit) {
    val batch = leveldb.createWriteBatch()
    try {
      p(batch)
      leveldb.write(batch, levelDbWriteOptions)
    } finally {
      batch.close()
    }
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