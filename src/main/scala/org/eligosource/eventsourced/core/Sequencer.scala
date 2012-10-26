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
package org.eligosource.eventsourced.core

import akka.actor.Actor

/**
 * Stackable modification for actors that need to receive a re-sequenced message stream.
 * `(sequence number, message)` tuples will be resequenced by this trait according to
 * `sequence number` where the modified actor's `receive` method is called with the re-
 * sequenced `message`s. Messages with types other than `(Long, Any)` by-pass the re-
 * sequencing algorithm.
 */
trait Sequencer extends Actor {
  import scala.collection.mutable.Map

  private val delayed = Map.empty[Long, Any]
  private var delivered = 0L

  abstract override def receive: Receive = {
    case (seqnr: Long, msg) => {
      resequence(seqnr, msg)
    }
    case msg => {
      super.receive(msg)
    }
  }

  @scala.annotation.tailrec
  private def resequence(seqnr: Long, msg: Any) {
    if (seqnr == delivered + 1) {
      delivered = seqnr
      super.receive(msg)
    } else {
      delayed += (seqnr -> msg)
    }
    val eo = delayed.remove(delivered + 1)
    if (eo.isDefined) resequence(delivered + 1, eo.get)
  }
}

