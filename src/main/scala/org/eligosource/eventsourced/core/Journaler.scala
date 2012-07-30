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
  private val serializer = new JavaSerializer[Message]

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  var counter = 0L

  def receive = {
    case cmd: WriteMsg => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      execute(c)
      if (c.target != context.system.deadLetters) c.target ! c.message
      sender ! ()
    }
    case cmd: WriteMsgs => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      execute(c)
      if (c.target != context.system.deadLetters) c.messages.foreach(c.target.!)
      sender ! ()
    }
    case cmd: WriteAck => {
      execute(cmd)
      sender ! ()
    }
    case cmd: DeleteMsg => {
      execute(cmd)
      sender ! ()
    }
    case Replay(compId, chanId, fromNr, target) => {
      replay(compId, chanId, fromNr, msg => target ! msg.copy(sender = None))
      sender ! ()
    }
    case GetCounter => {
      sender ! getCounter
    }
  }

  def execute(cmd: WriteMsg) {
    val batch = leveldb.createWriteBatch()
    val msg = cmd.message
    try {
      // add message to batch
      counter = msg.sequenceNr
      val k = Key(cmd.componentId, cmd.channelId, msg.sequenceNr, 0)
      val m = msg.copy(sender = None)
      batch.put(CounterKeyBytes, counterToBytes(counter))
      batch.put(k.bytes, serializer.toBytes(m.copy(sender = None)))

      // optionally, add ack to batch
      cmd.ackSequenceNr.foreach { snr =>
        val k = Key(cmd.componentId, Channel.inputChannelId, snr, cmd.channelId)
        batch.put(k.bytes, Array.empty[Byte])
      }
      leveldb.write(batch, levelDbWriteOptions)
      counter = counter + 1
    } finally {
      batch.close()
    }
  }

  def execute(cmd: WriteMsgs) {
    val batch = leveldb.createWriteBatch()
    try {
      // add all messages to batch
      cmd.messages.foreach { msg =>
        counter = msg.sequenceNr
        val k = Key(cmd.componentId, cmd.channelId, msg.sequenceNr, 0)
        val m = msg.copy(sender = None)
        batch.put(CounterKeyBytes, counterToBytes(counter))
        batch.put(k.bytes, serializer.toBytes(m.copy(sender = None)))
      }
      // optionally, add ack to batch
      cmd.ackSequenceNr.foreach { snr =>
        val k = Key(cmd.componentId, Channel.inputChannelId, snr, cmd.channelId)
        batch.put(k.bytes, Array.empty[Byte])
      }
      leveldb.write(batch, levelDbWriteOptions)
      counter = counter + 1
    } finally {
      batch.close()
    }
  }

  def execute(cmd: WriteAck) {
    val k = Key(cmd.componentId, Channel.inputChannelId, cmd.ackSequenceNr, cmd.channelId)
    leveldb.put(k.bytes, Array.empty[Byte], levelDbWriteOptions)
  }

  def execute(cmd: DeleteMsg) {
    val k = Key(cmd.componentId, cmd.channelId, cmd.msgSequenceNr, 0)
    leveldb.delete(k.bytes, levelDbWriteOptions)
  }

  override def preStart() {
    counter = getCounter
  }

  override def postStop() {
    leveldb.close()
  }

  private def getCounter = leveldb.get(CounterKeyBytes, levelDbReadOptions) match {
    case null  => 1L
    case bytes => bytesToCounter(bytes) + 1L
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
        val msg = serializer.fromBytes(nextEntry.getValue)
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

}

object Journaler {
  case class WriteMsg(componentId: Int, channelId: Int, message: Message, ackSequenceNr: Option[Long], target: ActorRef, genSequenceNr: Boolean = true) {
    def forSequenceNr(snr: Long) = {
      copy(message = message.copy(sequenceNr = snr), genSequenceNr = false)
    }
  }
  case class WriteMsgs(componentId: Int, channelId: Int, messages: List[Message], ackSequenceNr: Option[Long], target: ActorRef, genSequenceNr: Boolean = true) {
    def forSequenceNr(snr: Long) = {
      var ctr = snr - 1
      copy(messages = messages.map { m => ctr = ctr + 1; m.copy(sequenceNr = ctr) }, genSequenceNr = false)
    }
  }

  case class WriteAck(componentId: Int, channelId: Int, ackSequenceNr: Long)
  case class DeleteMsg(componentId: Int, channelId: Int, msgSequenceNr: Long)
  case class Replay(componentId: Int, channelId: Int, fromSequenceNr: Long, target: ActorRef)

  case object GetCounter

  private val CounterKeyBytes = Key(0, 0, 0L, 0).bytes

  private def counterToBytes(value: Long) =
    ByteBuffer.allocate(8).putLong(value).array

  private def bytesToCounter(bytes: Array[Byte]) =
    ByteBuffer.wrap(bytes).getLong

  private case class Key(
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

  private case object Key {
    def apply(bytes: Array[Byte]): Key = {
      val bb = ByteBuffer.wrap(bytes)
      val componentId = bb.getInt
      val initiatingChannelId = bb.getInt
      val sequenceNumber = bb.getLong
      val confirmingChannelId = bb.getInt
      new Key(componentId, initiatingChannelId, sequenceNumber, confirmingChannelId)
    }
  }
}

class ReplicatingJournaler(journaler: ActorRef) extends Actor {
  import Journaler._
  import Replicator._

  implicit val executor = context.dispatcher

  var messageCounter = 1L
  var deliveryCounter = 1L

  var replicator: Option[ActorRef] = None
  var sequencer: ActorRef = _

  val success = Promise.successful(())

  def receive = {
    case SetReplicator(r) => {
      replicator = r
    }
    case cmd: Replay => {
      journaler forward cmd
    }
    case cmd: WriteMsg => {
      val d = deliveryCounter
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(messageCounter) else cmd
      execute(c.copy(target = context.system.deadLetters)) {
        sequencer ! (d, (c.target, c.message))
      }
      deliveryCounter = deliveryCounter + 1
      messageCounter = c.message.sequenceNr + 1
    }
    case cmd: WriteMsgs => {
      val d = deliveryCounter
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(messageCounter) else cmd
      execute(c.copy(target = context.system.deadLetters)) {
        sequencer ! (d, (c.target, c.messages))
      }
      deliveryCounter = deliveryCounter + 1
      messageCounter = cmd.messages.lastOption.map(_.sequenceNr).getOrElse(messageCounter) + 1 // TODO: optimize
    }
    case cmd => {
      execute(cmd)(())
    }
  }

  def execute(cmd: Any)(onSuccess: => Unit) {
    val jf = journaler.ask(cmd)(journalerTimeout)
    val rf = if (replicator.isDefined) replicator.get.ask(cmd)(replicatorTimeout) else success
    val cf = Future.sequence(List(jf, rf))

    val s = sender

    cf onSuccess {
      case r => { s ! (); onSuccess }
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
            s ! ()
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

  def getCounter: Long = {
    val future = journaler.ask(GetCounter)(counterInitTimeout)
    Await.result(future.mapTo[Long], counterInitTimeout.duration)
  }

  override def preStart() {
    // initialize message counter from stored sequence number
    messageCounter = getCounter

    // use a delivery counter for re-sequencing future callbacks
    val deliveryCounter = this.deliveryCounter
    sequencer = context.actorOf(Props(new Sequencer {
      delivered = deliveryCounter - 1L
      def receiveSequenced = {
        case (target: ActorRef, message: Message) => {
          if (target != context.system.deadLetters) target ! message
        }
        case (target: ActorRef, messages: List[Message]) => {
          if (target != context.system.deadLetters) messages.foreach(m => target ! m)
        }
      }
    }))
  }
}

class Replicator(journaler: ActorRef, inputBufferLimit: Int = 100) extends Actor {
  import Journaler._
  import Replicator._

  var components = Map.empty[Int, Component]

  /**
   * EXPERIMENTAL
   *
   * Use an input buffer instead of directly sending messages to processor.
   * This may compensate for situations where input messages are replicated
   * but replicating the corresponding ACKs did not (or only partially) occur
   * during a master failure.
   */

  var inputBuffer = Queue.empty[(Int, Message)]
  var inputBufferSize = 0

  def receive = {
    case RegisterComponents(composite) => {
      components = composite.foldLeft(components) { (a, c) =>
        if (c.processor.isDefined) a + (c.id -> c) else a
      }
    }
    case CompleteReplication => {
      // compute replay starting position for components referenced from input buffer ...
      val replayFrom = inputBuffer.reverse.foldLeft(Map.empty[Int, Long]) { (a, e) =>
        val (cid, m) = e
        a + (cid -> m.sequenceNr)
      }

      // then replay ...
      for {
        (cid, snr) <- replayFrom
        component  <- components.get(cid)
      } component.replay(snr)

      // and deliver pending output messages
      components.values.foreach(_.deliver())

      // reply that components are recovered
      sender ! ()

      // and ignore further messages
      context.stop(self)
    }

    case cmd: WriteMsg => {
      if (cmd.channelId == Channel.inputChannelId) delay(cmd.componentId, cmd.message)
      journaler forward cmd.copy(target = context.system.deadLetters)
    }
    case cmd: WriteMsgs => {
      if (cmd.channelId == Channel.inputChannelId) cmd.messages.foreach(m => delay(cmd.componentId, m))
      journaler forward cmd.copy(target = context.system.deadLetters)
    }
    case cmd: WriteAck => {
      journaler forward cmd
    }
    case cmd: DeleteMsg => {
      journaler forward cmd
    }
  }

  def delay(componentId: Int, msg: Message) {
    inputBuffer = inputBuffer.enqueue(componentId, msg.copy(replicated = true))
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
  }
}

object Replicator {
  val counterInitTimeout = Timeout(5 seconds)
  val replicatorTimeout = Timeout(5 seconds)
  val journalerTimeout = Timeout(5 seconds)


  case class SetReplicator(replicator: Option[ActorRef])
  case class RegisterComponents(composite: Component)

  case object CompleteReplication

  def complete(replicator: ActorRef, duration: Duration) {
    Await.result(replicator.ask(CompleteReplication)(duration), duration)
  }
}