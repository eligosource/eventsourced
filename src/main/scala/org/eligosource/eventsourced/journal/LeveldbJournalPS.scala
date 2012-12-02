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

import java.io.{Closeable, File}
import java.nio.ByteBuffer

import scala.concurrent.duration._

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
private [eventsourced] abstract class LeveldbJournalPS(dir: File) extends Journal {
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

  def storedCounter = leveldb.get(CounterKeyBytes, levelDbReadOptions) match {
    case null  => 0L
    case bytes => counterFromBytes(bytes)
  }

  override def stop() {
    leveldb.close()
  }

  @scala.annotation.tailrec
  final def confirmingChannelIds(iter: DBIterator, key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (key.processorId         == nextKey.processorId &&
          key.initiatingChannelId == nextKey.initiatingChannelId &&
          key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
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

/**
 * Default [[org.eligosource.eventsourced.journal.LeveldbJournalPS]] implementation.
 */
private [eventsourced] class DefaultLeveldbJournal(dir: File) extends LeveldbJournalPS(dir) {
  import LeveldbJournalPS._

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => replay(cmd.processorId, 0, cmd.fromSequenceNr, msg => p(msg, cmd.target)))
    sender ! ReplayDone
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    replay(cmd.processorId, 0, cmd.fromSequenceNr, p)
    sender ! ReplayDone
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    replay(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr, p)
  }

  def replay(processorId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
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
  final def replay(iter: DBIterator, key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey, p)
      } else if (key.processorId         == nextKey.processorId &&
                 key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = serialization.deserializeMessage(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }
}

/**
 * A [[org.eligosource.eventsourced.journal.LeveldbJournalPS]] implementation that
 * supports throttled input message replay. Suspends replay for a certain duration,
 * specified by `throttleFor`, after every n replayed messages, specified by
 * `throttleAfter`.
 */
private [eventsourced] class ThrottledReplayJournal(dir: File, throttleAfter: Int, throttleFor: FiniteDuration, dispatcherName: Option[String] = None) extends LeveldbJournalPS(dir) {
  import LeveldbJournalPS._
  import ReplayThrottler._

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    val iterator = new LeveldbIterator(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr)
    try { iterator.foreach(p) } finally { iterator.close() }
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    executeBatchReplayInMsgs(List(cmd), (msg, _) => p(msg))
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    val replay = actor(new ThrottledReplay(cmds, p), dispatcherName = dispatcherName)
    replay forward Start
  }

  private class LeveldbIterator(processorId: Int, channelId: Int, fromSequenceNr: Long) extends Iterator[Message] with Closeable {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    var key = Key(processorId, channelId, fromSequenceNr, 0)

    iter.seek(key)

    @scala.annotation.tailrec
    final def hasNext = if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (advance iterator)
        iter.next()
        hasNext
      } else {
        key.processorId         == nextKey.processorId &&
        key.initiatingChannelId == nextKey.initiatingChannelId
      }
    } else false

    def next() = {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      val msg = serialization.deserializeMessage(nextEntry.getValue)
      val channelIds = confirmingChannelIds(iter, nextKey, Nil)
      key = nextKey
      msg.copy(acks = channelIds)
    }

    def close() {
      iter.close()
    }
  }

  private class ThrottledReplay(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) extends Actor {
    import context.dispatcher
    import ReplayThrottler._

    val iteratorTargetPairs = cmds.toStream.map {
      case ReplayInMsgs(pid, snr, target) => (new LeveldbIterator(pid, 0, snr), target)
    }

    val messageTargetPairs = for {
      (iterator, target) <- iteratorTargetPairs
      message            <- iterator
    } yield (message, target)

    var initiator: Option[ActorRef] = None
    var todo = messageTargetPairs
    var ctr = 0

    // ---------------------------------------
    // TODO: send failure reply on exception
    // ---------------------------------------

    def receive = {
      case Start => {
        initiator = Some(sender)
        self ! Continue
      }
      case Continue => {
        if (todo.isEmpty) {
          initiator.foreach(_ ! ReplayDone)
          context.stop(self)
        } else if (ctr == throttleAfter) {
          ctr = 0
          context.system.scheduler.scheduleOnce(throttleFor, self, Continue)
        } else {
          val (message, target) = todo.head
          p(message, target)
          ctr += 1
          todo = todo.tail
          self ! Continue
        }
      }
    }

    override def postStop() {
      iteratorTargetPairs.foreach {
        case (iterator, _) => iterator.close()
      }
    }
  }

  private object ReplayThrottler {
    case object Start
    case object Continue
  }
}
