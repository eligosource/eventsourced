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
import java.util.concurrent.Executors

import scala.collection.JavaConverters._
import scala.collection.immutable.{Queue, SortedMap}

import akka.actor._
import journal.io.api._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.util.JavaSerializer

class JournalioJournal(dir: File)(implicit system: ActorSystem) extends Actor {

  // TODO: make configurable
  val serializer = new JavaSerializer[AnyRef]

  val inputReplayQueue = new InputReplayQueue
  val outputMessageCache = new OutputMessageCache

  val disposer = Executors.newSingleThreadScheduledExecutor()
  val journal = new Journal

  var commandListener: Option[ActorRef] = None
  var counter = 0L

  def receive = {
    case cmd: WriteMsg => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written

      // write input or output message
      val msgLoc = journal.write(serializer.toBytes(c.copy(message = m, target = null)), Journal.WriteType.SYNC)

      // optionally, add output message (written by reliable output channel) to cache
      if (c.channelId != Channel.inputChannelId) {
        val msgKey = Key(c.componentId, c.channelId, m.sequenceNr, 0)
        outputMessageCache.add(msgKey, msgLoc, m)
      }

      // optionally, write acknowledgement
      c.ackSequenceNr.foreach { snr =>
        journal.write(serializer.toBytes(WriteAck(c.componentId, c.channelId, snr)), Journal.WriteType.SYNC)
      }

      if (c.target != context.system.deadLetters) c.target ! c.message
      if (sender   != context.system.deadLetters) sender ! Ack

      counter = m.sequenceNr + 1
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteAck => {
      journal.write(serializer.toBytes(cmd), Journal.WriteType.SYNC)
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteMsg => {
      val msgKey = Key(cmd.componentId, cmd.channelId, cmd.msgSequenceNr, 0)
      val msgLoc = outputMessageCache.delete(msgKey)

      msgLoc.foreach(journal.delete)

      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case BatchReplayInput(replays) => {
      val starts = replays.foldLeft(Map.empty[Int, (Long, ActorRef)]) { (a, r) =>
        a + (r.componentId -> (r.fromSequenceNr, r.target))
      }
      replayInputMessages { (k, m) =>
        starts.get(k.componentId) match {
          case Some((fromSequenceNr, target)) if (m.sequenceNr >= fromSequenceNr) => target ! m.copy(sender = None)
          case _ => ()
        }
      }
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case r @ ReplayInput(compId, fromNr, target) => {
      // call receive directly instead sending to self
      // (replay must not interleave with other commands)
      receive(BatchReplayInput(List(r)))
    }
    case ReplayOutput(compId, chanId, fromNr, target) => {
      replayOutputMessages(compId, chanId, fromNr, msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  def replayInputMessages(p: (Key, Message) => Unit) {
    journal.redo().asScala.foreach { location =>
      serializer.fromBytes(location.getData) match {
        case cmd: WriteMsg if (cmd.channelId == Channel.inputChannelId) => {
          val msgKey = Key(cmd.componentId, cmd.channelId, cmd.message.sequenceNr, 0)
          inputReplayQueue.enqueue(msgKey, cmd.message)
        }
        case cmd: WriteMsg => {
          val msgKey = Key(cmd.componentId, cmd.channelId, cmd.message.sequenceNr, 0)
          outputMessageCache.add(msgKey, location, cmd.message)
        }
        case cmd: WriteAck => {
          val ackKey = Key(cmd.componentId, Channel.inputChannelId, cmd.ackSequenceNr, cmd.channelId)
          inputReplayQueue.ack(ackKey)
        }
      }
      if (inputReplayQueue.size > 20000 /* TODO: make configurable */ ) {
        val (k, m) = inputReplayQueue.dequeue(); p(k, m)
      }
    }
    inputReplayQueue.foreach { km => p(km._1, km._2) }
    inputReplayQueue.clear()
  }

  def replayOutputMessages(componentId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit) {
    outputMessageCache.messages(componentId, channelId, fromSequenceNr).foreach(p)
  }

  def getCounter: Long = {
    val cmds = journal.undo().asScala.map { location => serializer.fromBytes(location.getData) }
    val cmdo = cmds.collectFirst { case cmd: WriteMsg => cmd }
    cmdo.map(_.message.sequenceNr + 1).getOrElse(1L)
  }

  override def preStart() {
    dir.mkdirs()

    journal.setPhysicalSync(false)
    journal.setDirectory(dir)
    journal.setWriter(system.dispatcher)
    journal.setDisposer(disposer)
    journal.setChecksum(false)
    journal.open()

    counter = getCounter
  }

  override def postStop() {
    journal.close()
    disposer.shutdown()
  }
}

/**
 * Queues up input messages during replay for taking into account input
 * message acks that have been written after the input messages.
 */
private [journal] class InputReplayQueue extends Iterable[(Key, Message)] {
  var acks = Map.empty[Key, List[Int]]
  var msgs = Queue.empty[(Key, Message)]

  var len = 0

  def enqueue(msgKey: Key, msg: Message) {
    msgs = msgs.enqueue((msgKey, msg))
    len = len + 1
  }

  def dequeue(): (Key, Message) = {
    val ((msgKey, msg), q) = msgs.dequeue
    msgs = q
    len = len - 1
    acks.get(msgKey) match {
      case None     => (msgKey, msg)
      case Some(as) => {
        acks = acks - msgKey
        (msgKey, msg.copy(acks = as))
      }
    }
  }

  def ack(ackKey: Key) {
    val msgKey = ackKey.copy(confirmingChannelId = 0)
    acks.get(msgKey) match {
      case Some(as) => acks = acks + (msgKey -> (ackKey.confirmingChannelId :: as))
      case None     => acks = acks + (msgKey -> List(ackKey.confirmingChannelId))
    }
  }

  def iterator =
    msgs.iterator.map(km => (km._1, km._2.copy(acks = acks.getOrElse(km._1, Nil))))

  override def size =
    len

  def clear() {
    acks = Map.empty
    msgs = Queue.empty
    len = 0
  }
}

/**
 * Cache for output messages stored by reliable output channels.
 */
private [journal] class OutputMessageCache {
  var msgs = SortedMap.empty[Key, (Location, Message)]

  def add(msgKey: Key, msgLoc: Location, msg: Message) {
    msgs = msgs + (msgKey -> (msgLoc, msg))
  }

  def delete(msgKey: Key): Option[Location] = msgs.get(msgKey) match {
    case None => None
    case Some((loc, msg)) => {
      msgs = msgs - msgKey
      Some(loc)
    }
  }

  def messages(componentId: Int, channelId: Int, fromSequenceNr: Long): Iterable[Message] = {
    val from = Key(componentId, channelId, fromSequenceNr, 0)
    val to = Key(componentId, channelId, Long.MaxValue, 0)
    msgs.range(from, to).values.map(_._2)
  }
}
