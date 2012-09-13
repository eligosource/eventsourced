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
 *    (with optional lower bound) for n components.
 *  - efficient replay of output messages for individual components
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for individual component requires full scan
 *    (with optional lower bound)
 */
class LeveldbJournalSS(dir: File) extends Actor {
  import LeveldbJournalSS._

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  val outputWriteMsgCache = new OutputWriteMsgCache[Long]

  var commandListener: Option[ActorRef] = None
  var counter = 0L

  def receive = {
    case cmd: WriteMsg => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written
      val b = leveldb.createWriteBatch()

      val key = if (c.channelId == Channel.inputChannelId) {
        SSKey(In, m.sequenceNr, Channel.inputChannelId)
      } else {
        outputWriteMsgCache.update(c, m.sequenceNr)
        SSKey(Out, m.sequenceNr, cmd.channelId)
      }

      try {
        // add command to batch
        b.put(key, cmdSerializer.toBytes(c.copy(message = m, target = null)))
        // optionally, add acknowledgement to batch
        c.ackSequenceNr.foreach { snr => b.put(SSKey(In, snr, c.channelId), Array.empty[Byte]) }
        // write batch
        leveldb.write(b, levelDbWriteOptions)
      } finally {
        b.close()
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
    case cmd: DeleteMsg => {
      outputWriteMsgCache.update(cmd).foreach { loc => leveldb.delete(SSKey(Out, loc, 0)) }
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case BatchReplayInput(replays) => {
      val starts = replays.foldLeft(Map.empty[Int, (Long, ActorRef)]) { (a, r) =>
        a + (r.componentId -> (r.fromSequenceNr, r.target))
      }
      replay(In, starts.values.map(_._1).min) { (cmd, acks) =>
        starts.get(cmd.componentId) match {
          case Some((fromSequenceNr, target)) if (cmd.message.sequenceNr >= fromSequenceNr) => {
            target ! cmd.message.copy(sender = None, acks = acks)
          }
          case _ => {}
        }
      }
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case r: ReplayInput => {
      // call receive directly instead sending to self
      // (replay must not interleave with other commands)
      receive(BatchReplayInput(List(r)))
    }
    case ReplayOutput(compId, chanId, fromNr, target) => {
      outputWriteMsgCache.messages(compId, chanId, fromNr).foreach(msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  def replay(direction: Int, fromSequenceNr: Long)(p: (WriteMsg, List[Int]) => Unit): Unit = {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = SSKey(In, fromSequenceNr, 0)
      iter.seek(startKey)
      replay(iter, startKey)(p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def replay(iter: DBIterator, key: SSKey)(p: (WriteMsg, List[Int]) => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = bytesToKey(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey)(p)
      } else if (key.direction == nextKey.direction) {
        val cmd = cmdSerializer.fromBytes(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(cmd, channelIds)
        replay(iter, nextKey)(p)
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

  def getCounter = leveldb.get(CounterKeyBytes, levelDbReadOptions) match {
    case null  => 1L
    case bytes => bytesToCounter(bytes) + 1L
  }

  override def preStart() {
    counter = getCounter
    replay(Out, 0L) { (cmd, acks) => outputWriteMsgCache.update(cmd, cmd.message.sequenceNr) }
  }

  override def postStop() {
    leveldb.close()
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
  private val cmdSerializer = new JavaSerializer[WriteMsg]

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