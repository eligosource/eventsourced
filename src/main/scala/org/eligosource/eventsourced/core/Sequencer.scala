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
 * A (stateful) message sequencer. Senders send (sequenceNr, msg) pairs.
 */
trait Sequencer extends Actor {
  import scala.collection.mutable.Map

  /** Sequence number of last message that has been delivered by this sequencer. */
  def lastSequenceNr: Long

  /** Implemented by subclasses to received sequenced messages. */
  def receiveSequenced: Receive

  val delayed = Map.empty[Long, Any]
  var delivered = lastSequenceNr

  def receive = {
    case (seqnr: Long, msg) => {
      resequence(seqnr, msg)
    }
    case msg => {
      receiveSequenced(msg)
    }
  }

  @scala.annotation.tailrec
  private def resequence(seqnr: Long, msg: Any) {
    if (seqnr == delivered + 1) {
      delivered = seqnr
      receiveSequenced(msg)
    } else {
      delayed += (seqnr -> msg)
    }
    val eo = delayed.remove(delivered + 1)
    if (eo.isDefined) resequence(delivered + 1, eo.get)
  }
}

