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
package org.eligosource.eventsourced.journal

import java.nio.ByteBuffer

package object common {
  private [journal] implicit val ordering = new Ordering[Key] {
    def compare(x: Key, y: Key) =
      if (x.processorId != y.processorId)
        x.processorId - y.processorId
      else if (x.initiatingChannelId != y.initiatingChannelId)
        x.initiatingChannelId - y.initiatingChannelId
      else if (x.sequenceNr != y.sequenceNr)
        math.signum(x.sequenceNr - y.sequenceNr).toInt
      else if (x.confirmingChannelId != y.confirmingChannelId)
        x.confirmingChannelId - y.confirmingChannelId
      else 0
  }

  implicit def counterToBytes(ctr: Long): Array[Byte] =
    ByteBuffer.allocate(8).putLong(ctr).array

  implicit def counterFromBytes(bytes: Array[Byte]): Long =
    ByteBuffer.wrap(bytes).getLong


}
