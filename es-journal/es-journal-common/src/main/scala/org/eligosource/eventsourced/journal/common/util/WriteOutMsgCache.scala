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
package org.eligosource.eventsourced.journal.common.util

import scala.collection.immutable.SortedMap

import org.eligosource.eventsourced.core.Message
import org.eligosource.eventsourced.core.JournalProtocol._

/**
 * Cache for WriteOutMsg commands.
 */
private [journal] class WriteOutMsgCache[L] {
  var cmds = SortedMap.empty[Key, (L, WriteOutMsg)]

  def update(cmd: WriteOutMsg, loc: L) {
    val key = Key(0, cmd.channelId, cmd.message.sequenceNr, 0)
    cmds = cmds + (key -> (loc, cmd))
  }

  def update(cmd: DeleteOutMsg): Option[L] = {
    val key = Key(0, cmd.channelId, cmd.msgSequenceNr, 0)
    cmds.get(key) match {
      case Some((loc, msg)) => { cmds = cmds - key; Some(loc) }
      case None             => None
    }
  }

  def messages(channelId: Int, fromSequenceNr: Long): Iterable[Message] = {
    val from = Key(0, channelId, fromSequenceNr, 0)
    val to = Key(0, channelId, Long.MaxValue, 0)
    cmds.range(from, to).values.map(_._2.message)
  }
}
