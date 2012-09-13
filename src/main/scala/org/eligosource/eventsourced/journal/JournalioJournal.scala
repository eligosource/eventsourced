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

class JournalioJournal(dir: File)(implicit system: ActorSystem) extends Actor {

  // TODO: make configurable
  val serializer = new JavaSerializer[AnyRef]

  val inputWriteMsgQueue = new InputWriteMsgQueue
  val outputWriteMsgCache = new OutputWriteMsgCache[Location]

  val disposer = Executors.newSingleThreadScheduledExecutor()
  val journal = new Journal

  var commandListener: Option[ActorRef] = None
  var counter = 0L

  def receive = {
    case cmd: WriteMsg => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      val m = c.message.copy(sender = None) // message to be written

      // write input or output message
      val loc = journal.write(serializer.toBytes(c.copy(message = m, target = null)), Journal.WriteType.SYNC)

      // optionally, add output message (written by reliable output channel) to cache
      if (c.channelId != Channel.inputChannelId) {
        outputWriteMsgCache.update(c, loc)
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
      outputWriteMsgCache.update(cmd).foreach(journal.delete)

      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case BatchReplayInput(replays) => {
      val starts = replays.foldLeft(Map.empty[Int, (Long, ActorRef)]) { (a, r) =>
        a + (r.componentId -> (r.fromSequenceNr, r.target))
      }
      replayInput { (cmd, acks) =>
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
      replayOutput(compId, chanId, fromNr, msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  def replayInput(p: (WriteMsg, List[Int]) => Unit) {
    journal.redo().asScala.foreach { location =>
      serializer.fromBytes(location.getData) match {
        case cmd: WriteMsg if (cmd.channelId == Channel.inputChannelId) => {
          inputWriteMsgQueue.enqueue(cmd)
        }
        case cmd: WriteMsg => {
          outputWriteMsgCache.update(cmd, location)
        }
        case cmd: WriteAck => {
          inputWriteMsgQueue.ack(cmd)
        }
      }
      if (inputWriteMsgQueue.size > 20000 /* TODO: make configurable */ ) {
        val (cmd, acks) = inputWriteMsgQueue.dequeue(); p(cmd, acks)
      }
    }
    inputWriteMsgQueue.foreach { ca => p(ca._1, ca._2) }
    inputWriteMsgQueue.clear()
  }

  def replayOutput(componentId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit) {
    outputWriteMsgCache.messages(componentId, channelId, fromSequenceNr).foreach(p)
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

object JournalioJournal {
  def apply(dir: File)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new JournalioJournal(dir)))
}
