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

import scala.concurrent.duration._

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.util.Timeout

import com.sclasen.spray.aws.dynamodb.DynamoDBClientProps

import org.apache.hadoop.fs.{FileSystem, Path}

import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.common.serialization.SnapshotSerializer
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshottingProps
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting.defaultLocalFilesystem

/**
 * CounterShards should be set to at least a 10x multiple of the expected throughput
 * of the journal during the lifetime of a journal the counterShards can only be
 * increased. Decreases in counterShards will be ignored. The larger the value of
 * counterShards, the longer it takes to recover the storedCounter.
 */
case class DynamoDBJournalProps(
  journalTable: String,
  eventSourcedApp: String,
  key: String,
  secret: String,
  operationTimeout: Timeout = Timeout(10 seconds),
  replayOperationTimeout: Timeout = Timeout(1 minute),
  counterShards:Int=10000,
  factory: Option[ActorRefFactory] = None,
  dynamoEndpoint:String = "dynamodb.us-east-1.amazonaws.com",
  name: Option[String] = None, dispatcherName: Option[String] = None,
  snapshotPath: Path = new Path("snapshots"),
  snapshotSerializer: SnapshotSerializer = SnapshotSerializer.java,
  snapshotLoadTimeout: FiniteDuration = 1 hour,
  snapshotSaveTimeout: FiniteDuration = 1 hour,
  snapshotFilesystem: FileSystem = defaultLocalFilesystem)
  extends JournalProps with HadoopFilesystemSnapshottingProps[DynamoDBJournalProps] {

  /**
   * Java API.
   */
  def withOperationTimeout(operationTimeout: Timeout) =
    copy(operationTimeout = operationTimeout)

  /**
   * Java API.
   */
  def withReplayOperationTimeout(oeplayOperationTimeout: Timeout) =
    copy(replayOperationTimeout = replayOperationTimeout)

  /**
   * Java API.
   */
  def withFactory(factory: ActorRefFactory) =
    copy(factory = Some(factory))

  /**
   * Java API.
   */
  def withDynamoEndpoint(dynamoEndpoint: String) =
    copy(dynamoEndpoint = dynamoEndpoint)

  /**
   * Java API.
   */
  def withSnapshotPath(snapshotPath: Path) =
    copy(snapshotPath = snapshotPath)

  /**
   * Java API.
   */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer) =
    copy(snapshotSerializer = snapshotSerializer)

  /**
   * Java API.
   */
  def withSnapshotLoadTimeout(snapshotLoadTimeout: FiniteDuration) =
    copy(snapshotLoadTimeout = snapshotLoadTimeout)

  /**
   * Java API.
   */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration) =
    copy(snapshotSaveTimeout = snapshotSaveTimeout)

  /**
   * Java API.
   */
  def withSnapshotFilesystem(snapshotFilesystem: FileSystem) =
    copy(snapshotFilesystem = snapshotFilesystem)

  def createJournalActor =
    new DynamoDBJournal(this)

  def clientProps(system:ActorSystem) =
    DynamoDBClientProps(key, secret, operationTimeout, system, factory.getOrElse(system), dynamoEndpoint)
}

object DynamoDBJournalProps {
  /**
   * Java API.
   */
  def create(journalTable: String, eventSourcedApp: String, key: String, secret: String) =
    DynamoDBJournalProps(journalTable, eventSourcedApp, key, secret)
}