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
import scala.collection.immutable.SortedMap

import akka.actor._
import journal.io.api._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.util.JavaSerializer

/**
 * Journal.IO based journal (experimental).
 */
object JournalioJournal {
  case class Entry(
    snr: Long,
    cmd: Any
  )

  case class Key(
    componentId: Int,
    initiatingChannelId: Int,
    sequenceNr: Long,
    confirmingChannelId: Int
  )

  implicit val keyOrdering = new Ordering[Key] {
    def compare(x: Key, y: Key) =
      if (x.componentId != y.componentId)
        x.componentId - y.componentId
      else if (x.initiatingChannelId != y.initiatingChannelId)
        x.initiatingChannelId - y.initiatingChannelId
      else if (x.sequenceNr != y.sequenceNr)
        math.signum(x.sequenceNr - y.sequenceNr).toInt
      else if (x.confirmingChannelId != y.confirmingChannelId)
        x.confirmingChannelId - y.confirmingChannelId
      else 0
  }
}

class JournalioJournal(dir: File)(implicit system: ActorSystem) extends Actor {
  import JournalioJournal._

  val serializer = new JavaSerializer[AnyRef]

  val disposer = Executors.newSingleThreadScheduledExecutor()
  val journal = new Journal

  var commandListener: Option[ActorRef] = None
  var counter = 0L

  var redoMap = SortedMap.empty[Key, Any]
  var redoFrom: Location = _

  def receive = {
    case cmd: WriteMsg => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      persist(c)
      if (c.target != context.system.deadLetters) c.target ! c.message
      if (sender   != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteAck => {
      persist(cmd)
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteMsg => {
      persist(cmd)
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case Replay(compId, chanId, fromNr, target) => {
      syncedRedoMap()
      replay(compId, chanId, fromNr, msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  def persist(cmd: WriteMsg) {
    val msg = cmd.message.copy(sender = None)
    journal.write(serializer.toBytes(cmd.copy(message = msg, target = null)), Journal.WriteType.SYNC)
    counter = msg.sequenceNr + 1
  }

  def persist(cmd: WriteAck) {
    journal.write(serializer.toBytes(cmd), Journal.WriteType.SYNC)
  }

  def persist(cmd: DeleteMsg) {
    journal.write(serializer.toBytes(cmd), Journal.WriteType.SYNC)
  }

  def syncedRedoMap() = {
    val iter = if (redoMap.isEmpty) journal.redo() else journal.redo(redoFrom)

    iter.asScala.foreach { location =>
      redoFrom = location
      serializer.fromBytes(location.getData) match {
        case cmd: WriteMsg => {
          val m = cmd.message
          val k = Key(cmd.componentId, cmd.channelId, m.sequenceNr, 0)
          redoMap = redoMap + (k -> m)

          cmd.ackSequenceNr.foreach { snr =>
            val k = Key(cmd.componentId, Channel.inputChannelId, snr, cmd.channelId)
            redoMap = redoMap + (k -> null)
          }
        }
        case cmd: WriteAck => {
          redoMap = redoMap + (Key(cmd.componentId, Channel.inputChannelId, cmd.ackSequenceNr, cmd.channelId) -> null)
        }
        case cmd: DeleteMsg => {
          redoMap = redoMap - Key(cmd.componentId, cmd.channelId, cmd.msgSequenceNr, 0)
        }
      }
    }
    redoMap
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

  private def getCounter: Long = {
    val cmds = journal.undo().asScala.map { location => serializer.fromBytes(location.getData) }
    val cmdo = cmds.collectFirst { case cmd: WriteMsg => cmd }
    cmdo.map(_.message.sequenceNr + 1).getOrElse(1L)
  }

  private def replay(componentId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val startKey = Key(componentId, channelId, fromSequenceNr, 0)
    val iter = syncedRedoMap().from(startKey).iterator.buffered
    replay(iter, startKey, p)
  }

  @scala.annotation.tailrec
  private def replay(iter: BufferedIterator[(Key, Any)], key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey   = nextEntry._1
      assert(nextKey.confirmingChannelId == 0)
      if (key.componentId         == nextKey.componentId &&
        key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = nextEntry._2.asInstanceOf[Message]
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: BufferedIterator[(Key, Any)], key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.head
      val nextKey = nextEntry._1
      if (key.componentId         == nextKey.componentId &&
        key.initiatingChannelId == nextKey.initiatingChannelId &&
        key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }
}
