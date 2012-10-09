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

import akka.actor._
import journal.io.api._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.util.JavaSerializer

/**
 * Journal.IO based journal.
 *
 * Pros:
 *
 *  - efficient replay of input messages for composites i.e. single scan
 *    (with optional lower bound) for n processors.
 *  - efficient replay of output messages
 *    (after initial replay of input messages)
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for individual processors requires full scan
 *    (with optional lower bound)
 */
private [eventsourced] class JournalioJournal(dir: File)(implicit system: ActorSystem) extends Actor {

  // TODO: make configurable
  val serializer = new JavaSerializer[AnyRef]

  val WriteInMsgQueue = new WriteInMsgQueue
  val WriteOutMsgCache = new WriteOutMsgCache[Location]

  val disposer = Executors.newSingleThreadScheduledExecutor()
  val journal = new Journal

  var commandListener: Option[ActorRef] = None
  var counter = 0L

  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written

      journal.write(serializer.toBytes(c.copy(message = m, target = null)), Journal.WriteType.SYNC)

      if (c.target != context.system.deadLetters) c.target ! c.message
      if (sender   != context.system.deadLetters) sender ! Ack

      counter = m.sequenceNr + 1
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteOutMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written

      val loc = journal.write(serializer.toBytes(c.copy(message = m, target = null)), Journal.WriteType.SYNC)

      WriteOutMsgCache.update(c, loc)

      if (c.ackSequenceNr != SkipAck) {
        val ac = WriteAck(c.ackProcessorId, c.channelId, c.ackSequenceNr)
        journal.write(serializer.toBytes(ac), Journal.WriteType.SYNC)
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
    case cmd: DeleteOutMsg => {
      WriteOutMsgCache.update(cmd).foreach(journal.delete)
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
      replayInput { (cmd, acks) =>
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
      replayOutput(chanId, fromNr, msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  def replayInput(p: (WriteInMsg, List[Int]) => Unit) {
    journal.redo().asScala.foreach { location =>
      serializer.fromBytes(location.getData) match {
        case cmd: WriteInMsg => {
          WriteInMsgQueue.enqueue(cmd)
        }
        case cmd: WriteOutMsg => {
          WriteOutMsgCache.update(cmd, location)
        }
        case cmd: WriteAck => {
          WriteInMsgQueue.ack(cmd)
        }
      }
      if (WriteInMsgQueue.size > 20000 /* TODO: make configurable */ ) {
        val (cmd, acks) = WriteInMsgQueue.dequeue(); p(cmd, acks)
      }
    }
    WriteInMsgQueue.foreach { ca => p(ca._1, ca._2) }
    WriteInMsgQueue.clear()
  }

  def replayOutput(channelId: Int, fromSequenceNr: Long, p: Message => Unit) {
    WriteOutMsgCache.messages(channelId, fromSequenceNr).foreach(p)
  }

  def getCounter: Long = {
    val cmds = journal.undo().asScala.map { location => serializer.fromBytes(location.getData) }
    val cmdo = cmds.collectFirst { case cmd: WriteInMsg => cmd }
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

object JournalioJournal {
  /**
   * Creates a [[https://github.com/sbtourist/Journal.IO Journal.IO]] based journal.
   *
   * Pros:
   *
   *  - efficient replay of input messages for composites i.e. single scan
   *    (with optional lower bound) for n processors.
   *  - efficient replay of output messages
   *    (after initial replay of input messages)
   *  - efficient deletion of old entries
   *
   * Cons:
   *
   *  - replay of input messages for individual processors requires full scan
   *    (with optional lower bound)
   *
   * @param dir journal directory
   */
  def apply(dir: File)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new JournalioJournal(dir)))
}
