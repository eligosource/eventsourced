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

import scala.concurrent.duration._

import akka.actor._

import org.eligosource.eventsourced.core.Journal

object LeveldbJournal {
  /**
   * Creates a [[http://code.google.com/p/leveldb/ LevelDB]] based journal that
   * organizes entries primarily based on processor id.
   *
   * Pros:
   *
   *  - efficient replay of input messages for all processors (batch replay)
   *  - efficient replay of input messages for a single processor
   *  - efficient replay of output messages
   *
   * Cons:
   *
   *  - deletion of old entries requires full scan
   *
   * @param dir journal directory.
   * @param name optional name of the journal actor in the underlying actor system.
   * @param dispatcherName optional dispatcher name.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def processorStructuredDefault(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    Journal(new DefaultLeveldbJournal(dir), name, dispatcherName)

  /**
   * Creates a [[http://code.google.com/p/leveldb/ LevelDB]] based journal that
   * organizes entries primarily based on processor id and additionally supports
   * throttled input message replay. The journal suspends replay for a certain
   * duration, specified by `throttleFor`, after every n replayed messages,
   * specified by `throttleAfter`.
   *
   * Pros:
   *
   *  - efficient replay of input messages for all processors (batch replay), optionally throttled
   *  - efficient replay of input messages for a single processor, optionally throttled
   *  - efficient replay of output messages
   *  - avoids growing of mailboxes of slow processors during replay.
   *
   * Cons:
   *
   *  - deletion of old entries requires full scan
   *
   * @param dir journal directory.
   * @param name optional name of the journal actor in the underlying actor system.
   * @param dispatcherName optional dispatcher name.
   * @param throttleAfter number of messages after which replay is suspended.
   * @param throttleFor duration to suspend replay.
   *
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def processorStructuredThrottled(dir: File, throttleAfter: Int = 10000, throttleFor: FiniteDuration = 100 milliseconds,
      name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    Journal(new ThrottledReplayJournal(dir, throttleAfter, throttleFor), name, dispatcherName)

  /**
   * Creates a [[http://code.google.com/p/leveldb/ LevelDB]] based journal that
   * organizes entries primarily based on sequence numbers, keeping input and
   * output messages separated.
   *
   * Pros:
   *
   *  - efficient replay of input messages for all processors (batch replay with optional lower bound).
   *  - efficient replay of output messages
   *  - efficient deletion of old entries
   *
   * Cons:
   *
   *  - replay of input messages for a single processor requires full scan (with optional lower bound)
   *
   * @param dir journal directory
   * @param name optional name of the journal actor in the underlying actor system.
   * @param dispatcherName optional dispatcher name.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def sequenceStructured(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem): ActorRef =
    Journal(new LeveldbJournalSS(dir), name, dispatcherName)

  /**
   * Creates a LevelDB based journal that organizes entries primarily based on processor id.
   *
   * @param dir journal directory
   * @param name optional name of the journal actor in the underlying actor system.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   * @see `LeveldbJournal.processorStructuredDefault`
   */
  def apply(dir: File, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit system: ActorSystem) =
    processorStructuredDefault(dir, name = name, dispatcherName = dispatcherName)
}
