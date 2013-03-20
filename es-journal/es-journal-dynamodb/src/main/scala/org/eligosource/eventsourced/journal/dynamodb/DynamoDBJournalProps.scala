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
package org.eligosource.eventsourced.journal.dynamodb

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.util.Timeout
import com.sclasen.spray.aws.dynamodb.DynamoDBClientProps
import concurrent.duration._
import org.eligosource.eventsourced.core.JournalProps

/*
counterShards should be set to at least a 10x multiple of the expected throughput of the journal
during the lifetime of a journal the counterShards can only be increased. Decreases in counterShards will be ignored.

the larger the value of counterShards, the longer it takes to recover the storedCounter
*/
case class DynamoDBJournalProps(journalTable: String, eventSourcedApp: String,
                                key: String, secret: String,
                                operationTimeout: Timeout = Timeout(10 seconds),
                                replayOperationTimeout: Timeout = Timeout(1 minute),
                                asyncWriterCount: Int = 16, counterShards:Int=10000,
                                system: ActorSystem, factory: Option[ActorRefFactory] = None,
                                dynamoEndpoint:String = "dynamodb.us-east-1.amazonaws.com",
                                val name: Option[String] = None, val dispatcherName: Option[String] = None) extends JournalProps {

  /**
   * Creates a [[org.eligosource.eventsourced.core.Journal]] actor instance.
   */
  def journal = new DynamoDBJournal(this)

  def clientProps = DynamoDBClientProps(key, secret, operationTimeout, system, factory.getOrElse(system), dynamoEndpoint)

}