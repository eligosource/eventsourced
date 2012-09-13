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
package org.eligosource.eventsourced

import java.nio.ByteBuffer

import scala.collection.immutable.{Queue, SortedMap}

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.util.Serializer

package object journal {
  private [journal] implicit val ordering = new Ordering[Key] {
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

  private [journal] val ctrSerializer = new Serializer[Long] {
    def toBytes(ctr: Long): Array[Byte] =
      ByteBuffer.allocate(8).putLong(ctr).array

    def fromBytes(bytes: Array[Byte]): Long =
      ByteBuffer.wrap(bytes).getLong
  }

  private [journal] implicit def counterToBytes(ctr: Long): Array[Byte] = ctrSerializer.toBytes(ctr)
  private [journal] implicit def bytesToCounter(bytes: Array[Byte]): Long = ctrSerializer.fromBytes(bytes)

  /**
   * Queue for input WriteMsg commands including a mechanism for matching
   * corresponding acknowledgements.
   */
  private [journal] class InputWriteMsgQueue extends Iterable[(WriteMsg, List[Int])] {
    var cmds = Queue.empty[WriteMsg]
    var acks = Map.empty[Key, List[Int]]

    var len = 0

    def enqueue(cmd: WriteMsg) {
      cmds = cmds.enqueue(cmd)
      len = len + 1
    }

    def dequeue(): (WriteMsg, List[Int]) = {
      val (cmd, q) = cmds.dequeue
      val key = Key(cmd.componentId, cmd.channelId, cmd.message.sequenceNr, 0)
      cmds = q
      len = len - 1
      acks.get(key) match {
        case Some(as) => { acks = acks - key; (cmd, as) }
        case None     => (cmd, Nil)
      }
    }

    def ack(cmd: WriteAck) {
      val key = Key(cmd.componentId, Channel.inputChannelId, cmd.ackSequenceNr, 0)
      acks.get(key) match {
        case Some(as) => acks = acks + (key -> (cmd.channelId :: as))
        case None     => acks = acks + (key -> List(cmd.channelId))
      }
    }

    def iterator =
      cmds.iterator.map(c => (c, acks.getOrElse(Key(c.componentId, c.channelId, c.message.sequenceNr, 0), Nil)))

    override def size =
      len

    def clear() {
      acks = Map.empty
      cmds = Queue.empty
      len = 0
    }
  }

  /**
   * Cache for output WriteMsg commands.
   */
  private [journal] class OutputWriteMsgCache[L] {
    var cmds = SortedMap.empty[Key, (L, WriteMsg)]

    def update(cmd: WriteMsg, loc: L) {
      val key = Key(cmd.componentId, cmd.channelId, cmd.message.sequenceNr, 0)
      cmds = cmds + (key -> (loc, cmd))
    }

    def update(cmd: DeleteMsg): Option[L] = {
      val key = Key(cmd.componentId, cmd.channelId, cmd.msgSequenceNr, 0)
      cmds.get(key) match {
        case Some((loc, msg)) => { cmds = cmds - key; Some(loc) }
        case None             => None
      }
    }

    def messages(componentId: Int, channelId: Int, fromSequenceNr: Long): Iterable[Message] = {
      val from = Key(componentId, channelId, fromSequenceNr, 0)
      val to = Key(componentId, channelId, Long.MaxValue, 0)
      cmds.range(from, to).values.map(_._2.message)
    }
  }
}