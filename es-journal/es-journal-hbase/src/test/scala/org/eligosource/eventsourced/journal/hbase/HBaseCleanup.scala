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
package org.eligosource.eventsourced.journal.hbase

import org.apache.hadoop.hbase.client._

trait HBaseCleanup {
  def client: HTable

  def cleanup() {
    import scala.collection.mutable.Buffer
    import scala.collection.JavaConverters._

    val deletes = for {
      i <- 0 to 3
      j <- 0 to 10
      k <- 0 to 100
    } yield Seq(
        new Delete(InMsgKey(i, j, k).toBytes),
        new Delete(OutMsgKey(i, j, k).toBytes))

    client.delete(Buffer(deletes.flatten: _*).asJava)
  }
}
