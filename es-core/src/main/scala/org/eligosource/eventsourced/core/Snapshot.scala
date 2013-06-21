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
package org.eligosource.eventsourced.core

import akka.actor._

/**
 * Snapshot metadata.
 */
trait SnapshotMetadata {
  /** Processor id */
  def processorId: Int
  /** Sequence number at which the snapshot was taken by a processor */
  def sequenceNr: Long
  /** Time at which the snapshot was saved by a journal */
  def timestamp: Long
}

/**
 * Snapshot of processor state.
 *
 * @param state Processor-specific state.
 */
case class Snapshot(processorId: Int, sequenceNr: Long, timestamp: Long, state: Any) extends SnapshotMetadata {
  private [eventsourced] def withTimestamp(timestamp: Long): Snapshot = copy(timestamp = timestamp)
  private [eventsourced] def withTimestamp: Snapshot = withTimestamp(System.currentTimeMillis)
}

/**
 * Requests a snapshot capturing action from an [[org.eligosource.eventsourced.core.Eventsourced]]
 * processor. Received by processor.
 */
case class SnapshotRequest(processorId: Int, sequenceNr: Long, requestor: ActorRef) {
  /**
   * Captures a snapshot of the receiving processor's state. The processor must call this method
   * during execution of its `receive` method.
   *
   * @param state current processor state.
   */
  def process(state: Any)(implicit context: ActorContext) = {
    context.sender.tell(JournalProtocol.SaveSnapshot(Snapshot(processorId, sequenceNr, 0L, state)), requestor)
  }
}

/**
 * Command for requesting a snapshot capturing action from a processor. Once a processor
 * processed the received [[org.eligosource.eventsourced.core.SnapshotRequest]] message
 * (by calling the message's `process` method with its current state) the captured snapshot
 * will be saved. The sender of this command will receive a [[org.eligosource.eventsourced.core.SnapshotSaved]]
 * reply when saving successfully completed.
 */
object SnapshotRequest {
  /**
   * Java API.
   *
   * Returns this object.
   */
  def get = this
}

/**
 * Offers a snapshot to a processor during replay.
 *
 * @see [[org.eligosource.eventsourced.core.ReplayParams]]
 */
case class SnapshotOffer(snapshot: Snapshot)

/**
 * Success reply to a snapshot capturing request.
 *
 * @see [[org.eligosource.eventsourced.core.SnapshotRequest$]]
 */
case class SnapshotSaved(processorId: Int, sequenceNr: Long, timestamp: Long) extends SnapshotMetadata

/**
 * Failure reply to a snapshot capturing request.
 */
class SnapshotNotSupportedException(message: String) extends RuntimeException(message) with Serializable
