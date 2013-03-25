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
package org.eligosource.eventsourced.journal.mongodb.casbah

import akka.actor._

import com.mongodb.casbah.Imports._

import org.eligosource.eventsourced.core._

/**
 * Configuration object for an Mongodb/Casbah based journal.
 *
 * Journal actors can be created from a configuration object as follows:
 *
 * {{{
 *  import akka.actor._
 *
 *  import org.eligosource.eventsourced.core.Journal
 *  import org.eligosource.eventsourced.journal.mongodb.casbah.MongodbCasbahJournalProps
 *
 *  implicit val system: ActorSystem = ...
 *
 *  val journal: ActorRef = Journal(MongodbCasbahJournalProps(journalConn))
 * }}}
 *
 * @param mongoClient Required mongoDB/Casbah client.
 * @param dbName Required mongoDB database name.
 * @param collName Required mongoDB collection name.
 * @param name Optional journal actor name.
 * @param dispatcherName Optional journal actor dispatcher name.
 */
case class MongodbCasbahJournalProps(
  mongoClient: MongoClient,
  dbName: String,
  collName: String,
  name: Option[String] = None,
  dispatcherName: Option[String] = None) extends JournalProps {

  /**
   * Returns a new `MongodbCasbahJournalProps` with specified journal actor name.
   */
  def withName(name: String) = copy(name = Some(name))

  /**
   * Returns a new `MongodbCasbahJournalProps` with specified journal actor dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) = copy(dispatcherName = Some(dispatcherName))

  def journal: Actor = new MongodbCasbahJournal(this)
}
