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

import scala.concurrent.duration._

import akka.actor.Actor

import org.eligosource.eventsourced.core.JournalProps

case class AsyncHBaseJournalProps(
  zookeeperQuorumSpec: String,
  name: Option[String] = None,
  dispatcherName: Option[String] = None,
  asyncWriterCount: Int = 3,
  asyncWriteTimeout: FiniteDuration = 10 seconds) extends JournalProps {

  def withName(name: String) =
    copy(name = Some(name))

  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  def withAsyncWriterCount(asyncWriterCount: Int) =
    copy(asyncWriterCount = asyncWriterCount)

  def withAsyncWriteTimeout(asyncWriteTimeout: FiniteDuration) =
    copy(asyncWriteTimeout = asyncWriteTimeout)

  def journal: Actor =
    new AsyncHBaseJournal(this)
}
