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
class InmemJournal extends Actor {
  var commandListener: Option[ActorRef] = None
  var redoMap = SortedMap.empty[Key, Any]
  var counter = 0L

  def receive = {
    case cmd: WriteMsg => {
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(counter) else cmd
      store(c)
      if (c.target != context.system.deadLetters) c.target ! c.message
      if (sender   != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case cmd: WriteAck => {
      store(cmd)
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteMsg => {
      store(cmd)
      if (sender != context.system.deadLetters) sender ! Ack
      commandListener.foreach(_ ! cmd)
    }
    case BatchReplayInput(replays) => {
      // call receive directly instead sending to self
      // (replay must not interleave with other commands)
      replays.foreach(receive)
    }
    case ReplayInput(compId, fromNr, target) => {
      replay(compId, Channel.inputChannelId, fromNr, msg => target ! msg.copy(sender = None))
      if (sender != context.system.deadLetters) sender ! Ack
    }
    case ReplayOutput(compId, chanId, fromNr, target) => {
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

  def store(cmd: WriteMsg) {
    val msg = cmd.message

    counter = msg.sequenceNr
    val k = Key(cmd.componentId, cmd.channelId, msg.sequenceNr, 0)
    val m = msg.copy(sender = None)
    redoMap = redoMap + (k -> m)

    cmd.ackSequenceNr.foreach { snr =>
      val k = Key(cmd.componentId, Channel.inputChannelId, snr, cmd.channelId)
      redoMap = redoMap + (k -> null)
    }

    counter = counter + 1
  }

  def store(cmd: WriteAck) {
    val k = Key(cmd.componentId, Channel.inputChannelId, cmd.ackSequenceNr, cmd.channelId)
    redoMap = redoMap + (k -> null)
  }

  def store(cmd: DeleteMsg) {
    val k = Key(cmd.componentId, cmd.channelId, cmd.msgSequenceNr, 0)
    redoMap = redoMap - k
  }

  def getCounter = counter + 1

  override def preStart() {
    counter = getCounter
  }

  def replay(componentId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val startKey = Key(componentId, channelId, fromSequenceNr, 0)
    val iter = redoMap.from(startKey).iterator.buffered
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

object InmemJournal {
  def apply()(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new InmemJournal))
}