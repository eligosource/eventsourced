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

import java.io.File

import akka.actor._

object LeveldbJournal {
  /**
   * Creates a [[http://code.google.com/p/leveldb/ LevelDB]] based journal that
   * organizes entries primarily based on processor id.
   *
   * Pros:
   *
   *  - efficient replay of input messages for composites
   *  - efficient replay of input messages for individual processors
   *  - efficient replay of output messages
   *
   * Cons:
   *
   *  - deletion of old entries requires full scan
   *
   * @param dir journal directory
   */
  def processorStructured(dir: File)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new LeveldbJournalPS(dir)))

  /**
   * Creates a [[http://code.google.com/p/leveldb/ LevelDB]] based journal that
   * organizes entries primarily based on sequence numbers, keeping input and
   * output entries separated.
   *
   * Pros:
   *
   *  - efficient replay of input messages for composites i.e. single scan
   *    (with optional lower bound) for n processors.
   *  - efficient replay of output messages
   *  - efficient deletion of old entries
   *
   * Cons:
   *
   *  - replay of input messages for individual processors requires full scan
   *    (with optional lower bound)
   *
   * @param dir journal directory
   */
  def sequenceStructured(dir: File)(implicit system: ActorSystem): ActorRef =
    system.actorOf(Props(new LeveldbJournalSS(dir)))

  /**
   * Creates a LevelDB based journal that organizes entries primarily based on processor id.
   *
   * @see `LeveldbJournal.processorStructured`
   */
  def apply(dir: File)(implicit system: ActorSystem) =
    processorStructured(dir)
}
