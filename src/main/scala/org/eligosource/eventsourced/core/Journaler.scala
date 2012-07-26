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
package org.eligosource.eventsourced.core

import java.io.File
import java.nio.ByteBuffer

import collection.immutable.Queue

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.{Duration, Timeout}

import org.fusesource.leveldbjni.JniDBFactory._
import org.iq80.leveldb._

import org.eligosource.eventsourced.util.JavaSerializer

class Journaler(dir: File) extends Actor {
  import Journaler._

  // TODO: make configurable
  private val serializer = new JavaSerializer[Value]

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  def receive = {
    case WriteAck(key) => {
      write(key)
      sender ! ()
    }
    case WriteMsg(key, msg, target) => {
      write(key, msg.copy(sender = None))
      if (target != context.system.deadLetters) { target ! msg }
      sender ! ()
    }
    case WriteAckAndMsg(ackKey, msgKey, msg, target) => {
      write(ackKey, msgKey, msg.copy(sender = None))
      if (target != context.system.deadLetters) { target ! msg }
      sender ! ()
    }
    case DeleteMsg(key) => {
      delete(key)
      sender ! ()
    }
    case Replay(compId, chanId, fromNr, target) => {
      replay(compId, chanId, fromNr, msg => target ! msg.copy(sender = None))
      sender ! ()
    }
    case GetLastSequenceNr(compId, chanId) => {
      sender ! lastSequenceNr(compId, chanId)
    }
  }

  def write(key: Key): Unit =
    leveldb.put(key.bytes, serializer.toBytes(Value()), levelDbWriteOptions)

  def write(key: Key, message: Message): Unit =
    leveldb.put(key.bytes, serializer.toBytes(Value(message = message)), levelDbWriteOptions)

  def write(ackKey: Key, msgKey: Key, message: Message): Unit = {
    val batch = leveldb.createWriteBatch()
    try {
      batch.put(ackKey.bytes, serializer.toBytes(Value()))
      batch.put(msgKey.bytes, serializer.toBytes(Value(message = message)))
      leveldb.write(batch, levelDbWriteOptions)
    } finally {
      batch.close()
    }
  }

  def delete(key: Key): Unit =
    leveldb.delete(key.bytes, levelDbWriteOptions)

  override def postStop() {
    leveldb.close()
  }

  private def replay(componentId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = Key(componentId, channelId, fromSequenceNr, 0)
      iter.seek(startKey.bytes)
      replay(iter, startKey, p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def replay(iter: DBIterator, key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = Key(nextEntry.getKey)
      assert(nextKey.confirmingChannelId == 0)
      if (key.componentId         == nextKey.componentId &&
          key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = serializer.fromBytes(nextEntry.getValue).message
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
      val nextKey = Key(nextEntry.getKey)
      if (key.componentId         == nextKey.componentId &&
          key.initiatingChannelId == nextKey.initiatingChannelId &&
          key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }

  // --------------------------------------------------------
  //  Slow search of highest sequence number. This is
  //  because iterator.prev() doesn't work properly in
  //  leveldbjbi. Not on critical path, only needed on
  //  application start.
  // --------------------------------------------------------

  private def lastSequenceNr(componentId: Int, channelId: Int): Long = {
    val iter = leveldb.iterator()
    try {
      val startKey = Key(componentId, channelId, 0L, 0)
      iter.seek(startKey.bytes)
      lastSequenceNr(iter, startKey).sequenceNr
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def lastSequenceNr(iter: DBIterator, key: Key): Key = {
    if (iter.hasNext) {
      val nextKey = Key(iter.next().getKey)
      if (key.componentId         == nextKey.componentId &&
          key.initiatingChannelId == nextKey.initiatingChannelId) lastSequenceNr(iter, nextKey) else key
    } else key
  }
}

object Journaler {
  case class DeleteMsg(key: Key)
  case class WriteMsg(key: Key, msg: Message, target: ActorRef)
  case class WriteAckAndMsg(ackKey: Key, msgKey: Key, msg: Message, target: ActorRef)
  case class WriteAck(key: Key)
  case class Replay(componentId: Int, channelId: Int, fromSequenceNr: Long, target: ActorRef)

  case class GetLastSequenceNr(componentId: Int, channelId: Int)

  case class Key(
    componentId: Int,
    initiatingChannelId: Int,
    sequenceNr: Long,
    confirmingChannelId: Int) {

    def bytes = {
      val bb = ByteBuffer.allocate(20)
      bb.putInt(componentId)
      bb.putInt(initiatingChannelId)
      bb.putLong(sequenceNr)
      bb.putInt(confirmingChannelId)
      bb.array
    }
  }

  case object Key {
    def apply(bytes: Array[Byte]): Key = {
      val bb = ByteBuffer.wrap(bytes)
      val componentId = bb.getInt
      val initiatingChannelId = bb.getInt
      val sequenceNumber = bb.getLong
      val confirmingChannelId = bb.getInt
      new Key(componentId, initiatingChannelId, sequenceNumber, confirmingChannelId)
    }
  }

  private case class Value(timestamp: Long = System.currentTimeMillis(), message: Message = null)
}

class ReplicatingJournaler(journaler: ActorRef) extends Actor {
  import Journaler._
  import Replicator._

  implicit val executor = context.dispatcher

  var counter = 1L
  var replicator: Option[ActorRef] = None
  val sequencer: ActorRef = context.actorOf(Props(new Sequencer {
    delivered = 0L

    def receiveSequenced = {
      case (target: ActorRef, message) => target ! message
    }
  }))

  def receive = {
    case SetReplicator(r) => {
      replicator = r
    }
    case cmd: Replay => {
      journaler forward cmd
    }
    case cmd: GetLastSequenceNr => {
      journaler forward cmd
    }
    case cmd: WriteMsg => {
      val c = counter
      journalAndReplicate(cmd.copy(target = context.system.deadLetters)) {
        sequencer ! (c, (cmd.target, cmd.msg))
      }
      counter = counter + 1
    }
    case cmd: WriteAckAndMsg => {
      val c = counter
      journalAndReplicate(cmd.copy(target = context.system.deadLetters)) {
        sequencer ! (c, (cmd.target, cmd.msg))
      }
      counter = counter + 1
    }
    case cmd => {
      journalAndReplicate(cmd)(())
    }
  }

  def journalAndReplicate(cmd: Any)(success: => Unit) {
    val jf = journaler.ask(cmd)(journalerTimeout)
    val rf = if (replicator.isDefined) replicator.get.ask(cmd)(replicatorTimeout) else Promise.successful(())
    val cf = Future.sequence(List(jf, rf))

    val s = sender

    cf onSuccess {
      case r => { success; s ! () }
    }

    cf onFailure {
      case e => {
        val jfSuccess = jf.value.map(_.isRight).getOrElse(false)
        val rfSuccess = rf.value.map(_.isRight).getOrElse(false)

        (jfSuccess, rfSuccess) match {
          case (true, false) => {
            // continue at risk without replication
            self ! SetReplicator(None)
            // inform sender about journaling result
            s ! jf.value.get.right.get
            // ...
            // TODO: inform cluster manager to re-attach slave
          }
          case other => {
            sender ! Status.Failure(e)
          }
        }
      }
    }
  }
}

class Replicator(journaler: ActorRef) extends Actor {
  import Journaler._
  import Replicator._

  var components = Map.empty[Int, Component] // TODO: map to components

  var inputBuffer = Queue.empty[(Int, Message)]
  var inputBufferSize = 0

  //var completionStarted = false

  /**
   * EXPERIMENTAL:
   *
   * Use an input buffer instead of directly sending messages to input channels.
   * This may compensate for situations where input messages are replicated but
   * replicating the corresponding ACKs did not occur during a master failure.
   *
   * TODO: make inputBufferLimit configurable.
   */
  val inputBufferLimit = 10

  def receive = {
    case RegisterComponents(composite) => {
      components = composite.foldLeft(components) { (a, c) =>
        if (c.processor.isDefined) a + (c.id -> c) else a
      }
    }
    case CompleteReplication => {
      // synchronize channel counters with journal
      components.values.foreach(_.recount())

      // compute replay starting position for components referenced in input buffer
      val replayFrom = inputBuffer.reverse.foldLeft(Map.empty[Int, Long]) { (a, e) =>
        val (cid, m) = e
        a + (cid -> m.sequenceNr)
      }

      // then replay ...
      for {
        (cid, snr) <- replayFrom
        component  <- components.get(cid)
      } component.replay(snr)

      // and deliver pending messages
      components.values.foreach(_.deliver())

      // reply that components are recovered
      sender ! ()

      // will not receive further messages
      context.stop(self)
    }
    case cmd @ WriteMsg(key, msg, target) => {
      inputBuffer = inputBuffer.enqueue(key.componentId, msg.copy(replicated = true))
      inputBufferSize = inputBufferSize + 1

      if (inputBufferSize > inputBufferLimit) {
        val ((cid, m), b) = inputBuffer.dequeue
        inputBuffer = b
        inputBufferSize = inputBufferSize -1
        for {
          c <- components.get(cid)
          p <- c.processor
        } p ! m
      }

      journaler forward cmd.copy(target = context.system.deadLetters)
    }
    case cmd: WriteAckAndMsg => {
      journaler forward cmd.copy(target = context.system.deadLetters)
    }
    case cmd => {
      journaler forward cmd
    }
  }
}

object Replicator {
  val replicatorTimeout = Timeout(5 seconds)
  val journalerTimeout = Timeout(5 seconds)

  case class SetReplicator(replicator: Option[ActorRef])
  case class RegisterComponents(composite: Component)

  case object CompleteReplication

  def complete(replicator: ActorRef, duration: Duration) {
    Await.result(replicator.ask(CompleteReplication)(duration), duration)
  }
}