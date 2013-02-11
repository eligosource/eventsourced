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
package org.eligosource.eventsourced.journal.common

import scala.collection.immutable.Queue

import org.eligosource.eventsourced.core.Journal._

/**
 * Queue for WriteInMsg commands including a mechanism for matching acknowledgements.
 */
private [journal] class WriteInMsgQueue extends Iterable[(WriteInMsg, List[Int])] {
  var cmds = Queue.empty[WriteInMsg]
  var acks = Map.empty[Key, List[Int]]

  var len = 0

  def enqueue(cmd: WriteInMsg) {
    cmds = cmds.enqueue(cmd)
    len = len + 1
  }

  def dequeue(): (WriteInMsg, List[Int]) = {
    val (cmd, q) = cmds.dequeue
    val key = Key(cmd.processorId, 0, cmd.message.sequenceNr, 0)
    cmds = q
    len = len - 1
    acks.get(key) match {
      case Some(as) => { acks = acks - key; (cmd, as) }
      case None     => (cmd, Nil)
    }
  }

  def ack(cmd: WriteAck) {
    val key = Key(cmd.processorId, 0, cmd.ackSequenceNr, 0)
    acks.get(key) match {
      case Some(as) => acks = acks + (key -> (cmd.channelId :: as))
      case None     => acks = acks + (key -> List(cmd.channelId))
    }
  }

  def iterator =
    cmds.iterator.map(c => (c, acks.getOrElse(Key(c.processorId, 0, c.message.sequenceNr, 0), Nil)))

  override def size =
    len

  def clear() {
    acks = Map.empty
    cmds = Queue.empty
    len = 0
  }
}
