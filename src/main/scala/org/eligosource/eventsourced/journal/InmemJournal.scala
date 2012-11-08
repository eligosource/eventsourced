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

import scala.collection.immutable.SortedMap

import akka.actor._

import org.eligosource.eventsourced.core._

/**
 * In-memory journal for testing purposes.
 */
private [eventsourced] class InmemJournal extends Actor {
  var commandListener: Option[ActorRef] = None
  var redoMap = SortedMap.empty[Key, Any]
  var counter = 0L

  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else cmd
      storeInMsg(c)
      c.target forward Written(c.message)
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteOutMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else cmd
      storeOutMsg(c)
      c.target forward Written(c.message)
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteAck => {
      storeAck(cmd)
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteOutMsg => {
      storeDel(cmd)
      commandListener.foreach(_ ! cmd)
    }
    case Loop(msg, target) => {
      target forward (Looped(msg))
    }
    case BatchDeliverOutMsgs(channels) => {
      channels.foreach(_ ! Deliver)
    }
    case BatchReplayInMsgs(replays) => {
      replays.foreach(replayInMsgs)
    }
    case cmd: ReplayInMsgs => {
      replayInMsgs(cmd)
    }
    case ReplayOutMsgs(chanId, fromNr, target) => {
      replay(Int.MaxValue, chanId, fromNr, msg => target tell Written(msg))
    }
    case GetCounter => {
      sender ! getCounter
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  def storeInMsg(cmd: WriteInMsg) {
    val msg = cmd.message
    counter = msg.sequenceNr

    val k = Key(cmd.processorId, 0, counter, 0)
    redoMap = redoMap + (k -> msg.clearConfirmationSettings)

    counter = counter + 1
  }

  def storeOutMsg(cmd: WriteOutMsg) {
    val msg = cmd.message
    counter = msg.sequenceNr

    val k = Key(Int.MaxValue, cmd.channelId, counter, 0)
    redoMap = redoMap + (k -> msg.clearConfirmationSettings)

    if (cmd.ackSequenceNr != SkipAck) {
      val k = Key(cmd.ackProcessorId, 0, cmd.ackSequenceNr, cmd.channelId)
      redoMap = redoMap + (k -> null)
    }

    counter = counter + 1
  }

  def storeAck(cmd: WriteAck) {
    val k = Key(cmd.processorId, 0, cmd.ackSequenceNr, cmd.channelId)
    redoMap = redoMap + (k -> null)
  }

  def storeDel(cmd: DeleteOutMsg) {
    val k = Key(Int.MaxValue, cmd.channelId, cmd.msgSequenceNr, 0)
    redoMap = redoMap - k
  }

  def getCounter = counter + 1

  override def preStart() {
    counter = getCounter
  }

  def replayInMsgs(cmd: ReplayInMsgs) {
    replay(cmd.processorId, 0, cmd.fromSequenceNr, msg => cmd.target.tell(Written(msg)))
  }

  def replay(processorId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val startKey = Key(processorId, channelId, fromSequenceNr, 0)
    val iter = redoMap.from(startKey).iterator.buffered
    replay(iter, startKey, p)
  }

  @scala.annotation.tailrec
  private def replay(iter: BufferedIterator[(Key, Any)], key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey   = nextEntry._1
      assert(nextKey.confirmingChannelId == 0)
      if (key.processorId         == nextKey.processorId &&
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
      if (key.processorId         == nextKey.processorId &&
          key.initiatingChannelId == nextKey.initiatingChannelId &&
          key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }
}

/**
 * Creates an in-memory journal for testing purposes.
 */
object InmemJournal {
  def apply(name: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    if (name.isDefined)
      system.actorOf(Props(new InmemJournal), name.get) else
      system.actorOf(Props(new InmemJournal))
}