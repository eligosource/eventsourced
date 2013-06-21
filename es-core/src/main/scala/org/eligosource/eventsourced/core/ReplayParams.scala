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

import java.lang.{Boolean => JBoolean}

import akka.japi.{Function => JFunction}

/**
 * Processor-specific replay parameters.
 */
sealed abstract class ReplayParams {
  /**
   * Processor id.
   */
  def processorId: Int

  /**
   * Lower sequence number bound. Sequence number, where a replay should start from.
   * Only applicable if replay doesn't start from a snapshot (i.e. `snapshot` is `false`).
   */
  def fromSequenceNr: Long

  /**
   * Upper sequence number bound. Sequence number a replay should end (inclusive). Applicable
   * to both, snapshotted and non-snapshotted replays.
   */
  def toSequenceNr: Long

  /**
   * Whether or not replay should start from a snapshot.
   */
  def snapshot: Boolean

  /**
   * Filter applied to saved snapshots. The filtered snapshot with the highest sequence
   * number (if any) will be the replay starting point.
   */
  def snapshotFilter: SnapshotMetadata => Boolean

  /**
   * Updates `toSequenceNr` with specified value.
   */
  def withToSequenceNr(toSequenceNr: Long): ReplayParams
}

/**
 * @see [[org.eligosource.eventsourced.core.ReplayParams]]
 */
object ReplayParams {
  /**
   * Creates processor-specific replay parameters for non-snapshotted replay with optional
   * lower and upper sequence number bounds.
   */
  def apply(processorId: Int, fromSequenceNr: Long = 0L, toSequenceNr: Long = Long.MaxValue): ReplayParams =
    StandardReplayParams(processorId, fromSequenceNr, toSequenceNr)

  /**
   * Creates processor-specific replay parameters for snapshotted replay with no upper
   * sequence number bound.
   */
  def apply(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): ReplayParams =
    SnapshotReplayParams(processorId, snapshotFilter)

  /**
   * Creates processor-specific replay parameters for snapshotted replay with an upper
   * sequence number bound.
   */
  def apply(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean, toSequenceNr: Long): ReplayParams =
    SnapshotReplayParams(processorId, snapshotFilter, toSequenceNr)

  /**
   * Creates processor-specific replay parameters for snapshotted replay if `snapshot` is
   * `true`, for non-snapshotted replay otherwise. There are no lower and upper sequence
   * number bounds.
   */
  def apply(processorId: Int, snapshot: Boolean): ReplayParams =
    if (snapshot) SnapshotReplayParams(processorId)
    else StandardReplayParams(processorId)

  /**
   * Creates processor-specific replay parameters for snapshotted replay if `snapshot` is
   * `true`, for non-snapshotted replay otherwise. There is an upper sequence number bound.
   */
  def apply(processorId: Int, snapshot: Boolean, toSequenceNr: Long): ReplayParams =
    if (snapshot) SnapshotReplayParams(processorId, toSequenceNr = toSequenceNr)
    else StandardReplayParams(processorId, toSequenceNr = toSequenceNr)

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for non-snapshotted replay with no
   * lower and upper sequence number bounds.
   */
  def create(processorId: Int): ReplayParams =
    apply(processorId)

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for non-snapshotted replay with a
   * lower sequence number bound but no upper sequence number bound.
   */
  def create(processorId: Int, fromSequenceNr: Long): ReplayParams =
    apply(processorId, fromSequenceNr)

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for non-snapshotted replay with
   * lower and upper sequence number bounds.
   */
  def create(processorId: Int, fromSequenceNr: Long, toSequenceNr: Long): ReplayParams =
    apply(processorId, fromSequenceNr, toSequenceNr)

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for snapshotted replay with no upper
   * sequence number bound.
   */
  def create(processorId: Int, snapshotFilter: JFunction[SnapshotMetadata, JBoolean]): ReplayParams =
    apply(processorId, smd => snapshotFilter(smd))

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for snapshotted replay with an upper
   * sequence number bound.
   */
  def create(processorId: Int, snapshotFilter: JFunction[SnapshotMetadata, JBoolean], toSequenceNr: Long): ReplayParams =
    apply(processorId, smd => snapshotFilter(smd), toSequenceNr)

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for snapshotted replay if `snapshot` is
   * `true`, for non-snapshotted replay otherwise. There are no lower and upper sequence
   * number bounds.
   */
  def create(processorId: Int, snapshot: Boolean): ReplayParams =
    apply(processorId, snapshot)

  /**
   * Java API.
   *
   * Creates processor-specific replay parameters for snapshotted replay if `snapshot` is
   * `true`, for non-snapshotted replay otherwise. There is an upper sequence number bound.
   */
  def create(processorId: Int, snapshot: Boolean, toSequenceNr: Long): ReplayParams =
    apply(processorId, snapshot, toSequenceNr)

  /**
   * Processor-specific replay parameters for non-snapshotted replay.
   */
  case class StandardReplayParams(
    processorId: Int,
    fromSequenceNr: Long = 0L,
    toSequenceNr: Long = Long.MaxValue) extends ReplayParams {

    /**
     * Returns `false`.
     */
    val snapshot = false

    /**
     * Not applicable.
     */
    def snapshotFilter = _ => false

    /**
     * Updates `toSequenceNr` with specified value.
     */
    def withToSequenceNr(toSequenceNr: Long) =
      copy(toSequenceNr = toSequenceNr)
  }

  /**
   * Processor-specific replay parameters for snapshotted replay.
   *
   * @param snapshotBaseFilter application defined snapshot filter.
   *        Selects any saved snapshot by default.
   */
  case class SnapshotReplayParams(
    processorId: Int,
    snapshotBaseFilter: SnapshotMetadata => Boolean = _ => true,
    toSequenceNr: Long = Long.MaxValue) extends ReplayParams {

    /**
     * Returns `0L`.
     */
    val fromSequenceNr = 0L

    /**
     * Return `true`.
     */
    val snapshot = true

    /**
     * Snapshot filter that applies `snapshotBaseFilter` an and a
     * `<= toSequenceNr` constraint.
     */
    def snapshotFilter: SnapshotMetadata => Boolean =
      smd => snapshotBaseFilter(smd) && (smd.sequenceNr <= toSequenceNr)

    /**
     * Updates `toSequenceNr` with specified value.
     */
    def withToSequenceNr(toSequenceNr: Long) =
      copy(toSequenceNr = toSequenceNr)
  }
}
