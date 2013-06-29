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
package org.eligosource.eventsourced.journal.mongodb.reactive

import reactivemongo.api.{DB, MongoConnection, MongoDriver}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

trait MongodbReactiveFixtureSupport {

  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val duration = 10 seconds

  val connection = MongodbReactiveFixtureSupport.connection
  val journalProps = MongodbReactiveJournalProps(List("localhost:34567")).withDbName("journal2").withCollName("event2")

  def cleanup() {
    def cleanup() = try {
      val db = DB("journal2", connection)
      Await.result(db.drop(), duration)
    } catch { case _: Throwable => /* ignore */ }
    cleanup()
  }
}

object MongodbReactiveFixtureSupport {
  val driver = new MongoDriver
  val connection = driver.connection(List("localhost:34567"))
}
