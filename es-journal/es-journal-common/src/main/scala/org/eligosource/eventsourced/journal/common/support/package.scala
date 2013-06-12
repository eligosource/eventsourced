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

import org.eligosource.eventsourced.core.Message

package object support {
  /**
   * Decorates `p` to reset `Message.senderRef`. The `senderRef` is reset if the event message
   *
   *  - has a `sequenceNr < initialCounter` and
   *  - has a `senderRef` of type `PromiseActorRef`
   *
   * Otherwise, the `senderRef` is not changed. This ensures that references to temporary system
   * actors from previous application runs are not resolved.
   *
   * @param initialCounter initial counter value after journal recovery.
   * @param p event message handler.
   * @return decorated event message handler.
   */
  def resetPromiseActorRef(initialCounter: Long)(p: Message => Unit): Message => Unit = msg =>
    if (msg.senderRef == null) p(msg)
    // TODO: change to isInstanceOf[PromiseActorRef]
    else if (msg.senderRef.getClass.getSimpleName == "PromiseActorRef" && msg.sequenceNr < initialCounter) {
      p(msg.copy(senderRef = null))
    }
    else p(msg)
}
