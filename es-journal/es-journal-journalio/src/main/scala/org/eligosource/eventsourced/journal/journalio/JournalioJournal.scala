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
package org.eligosource.eventsourced.journal.journalio

import java.io.File
import java.util.concurrent.Executors

import scala.collection.JavaConverters._

import akka.actor._
import journal.io.api.{Journal => JournalIO, _}

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common._

/**
 * [[https://github.com/sbtourist/Journal.IO Journal.IO]] based journal.
 *
 * Pros:
 *
 *  - efficient replay of input messages for all processors (batch replay with optional lower bound).
 *  - efficient replay of output messages (after initial replay of input messages)
 *  - efficient deletion of old entries
 *
 * Cons:
 *
 *  - replay of input messages for a single processor requires full scan (with optional lower bound)
 */
private [eventsourced] class JournalioJournal(props: JournalioJournalProps) extends SequentialWriteJournal {
  import Journal._

  val writeInMsgQueue = new WriteInMsgQueue
  val writeOutMsgCache = new WriteOutMsgCache[Location]

  val disposer = Executors.newSingleThreadScheduledExecutor()
  val journal = new JournalIO

  val serialization = Serialization(context.system)

  implicit def cmdToBytes(cmd: AnyRef): Array[Byte] = serialization.serializeCommand(cmd)
  implicit def cmdFromBytes(bytes: Array[Byte]): AnyRef = serialization.deserializeCommand(bytes)

  def executeWriteInMsg(cmd: WriteInMsg) {
    val pmsg = cmd.message.clearConfirmationSettings
    val pcmd = cmd.copy(message = pmsg, target = null)
    journal.write(cmdToBytes(pcmd), JournalIO.WriteType.SYNC)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg) {
    val pmsg = cmd.message.clearConfirmationSettings
    val pcmd = cmdToBytes(cmd.copy(message = pmsg, target = null))

    val loc = journal.write(pcmd, JournalIO.WriteType.SYNC)

    if (cmd.ackSequenceNr != SkipAck) {
      val ac = WriteAck(cmd.ackProcessorId, cmd.channelId, cmd.ackSequenceNr)
      journal.write(cmdToBytes(ac), JournalIO.WriteType.SYNC)
    }

    writeOutMsgCache.update(cmd, loc)
  }

  def executeWriteAck(cmd: WriteAck) {
    journal.write(cmdToBytes(cmd), JournalIO.WriteType.SYNC)
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    writeOutMsgCache.update(cmd).foreach(journal.delete)
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    val starts = cmds.foldLeft(Map.empty[Int, (Long, ActorRef)]) { (a, r) =>
      a + (r.processorId -> (r.fromSequenceNr, r.target))
    }
    replayInput { (cmd, acks) =>
      starts.get(cmd.processorId) match {
        case Some((fromSequenceNr, target)) if (cmd.message.sequenceNr >= fromSequenceNr) => {
          p(cmd.message.copy(acks = acks), target)
        }
        case _ => {}
      }
    }
    sender ! ReplayDone
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    executeBatchReplayInMsgs(List(cmd), (msg, _) => p(msg))
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    writeOutMsgCache.messages(cmd.channelId, cmd.fromSequenceNr).foreach(p)
  }

  private def replayInput(p: (WriteInMsg, List[Int]) => Unit) {
    journal.redo().asScala.foreach { location =>
      cmdFromBytes(location.getData) match {
        case cmd: WriteInMsg => {
          writeInMsgQueue.enqueue(cmd)
        }
        case cmd: WriteOutMsg => {
          writeOutMsgCache.update(cmd, location)
        }
        case cmd: WriteAck => {
          writeInMsgQueue.ack(cmd)
        }
      }
      if (writeInMsgQueue.size > 20000 /* TODO: make configurable */ ) {
        val (cmd, acks) = writeInMsgQueue.dequeue(); p(cmd, acks)
      }
    }
    writeInMsgQueue.foreach { ca => p(ca._1, ca._2) }
    writeInMsgQueue.clear()
  }

  def storedCounter: Long = {
    val cmds = journal.undo().asScala.map { location => cmdFromBytes(location.getData) }
    val cmdo = cmds.collectFirst { case cmd: WriteInMsg => cmd }
    cmdo.map(_.message.sequenceNr).getOrElse(0L)
  }

  override def start() {
    props.dir.mkdirs()

    journal.setPhysicalSync(props.fsync)
    journal.setDirectory(props.dir)
    journal.setWriter(context.dispatcher)
    journal.setDisposer(disposer)
    journal.setChecksum(props.checksum)
    journal.open()
  }

  override def stop() {
    journal.close()
    disposer.shutdown()
  }
}

/**
 * @see [[org.eligosource.eventsourced.journal.JournalioJournalProps]]
 */
object JournalioJournal {
  @deprecated("use Journal(JournalioJournalProps(dir)) instead", "0.5")
  def apply(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    Journal(JournalioJournalProps(dir, name, dispatcherName))
}
