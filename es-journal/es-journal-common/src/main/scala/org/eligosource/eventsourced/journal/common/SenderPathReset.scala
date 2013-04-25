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

import akka.actor._

import org.eligosource.eventsourced.core.Message

/**
 * Used by journals to reset persistent sender paths on output event messages.
 */
trait SenderPathReset {
  /**
   * Returns the initial counter value after journal start.
   */
  def initialCounter: Long

  /**
   * Decorates `p` to reset `Message.senderPath`. The `senderPath` is reset if the event message
   *
   *  - has a `sequenceNr < initialCounter` and
   *  - has a `senderPath` that refers to a temporary (short-lived) system actor (e.g. a future)
   *
   * Otherwise, the `senderPath` is not changed. This ensures that references to temporary system
   * actors from previous application runs are not resolved.
   *
   * @param p event message handler.
   * @return decorated event message handler.
   */
  def resetTempPath(p: Message => Unit): Message => Unit = msg =>
    if (msg.senderPath == null) p(msg)
    else if (ActorPath.fromString(msg.senderPath).elements.head == "temp" && msg.sequenceNr < initialCounter) p(msg.copy(senderPath = null))
    else p(msg)
}
