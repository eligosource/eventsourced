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
package org.eligosource.eventsourced.journal.common.serialization

import java.io._

import akka.util.ClassLoaderObjectInputStream

import org.eligosource.eventsourced.core._

/**
 * Snapshot (de)serialization utility.
 */
trait SnapshotSerialization {
  /** Stream manager for snapshot IO */
  def snapshotAccess: SnapshotAccess
  /** Snapshot serializer */
  def snapshotSerializer: SnapshotSerializer

  /**
   * Serializes a snapshot using `snapshotSerializer` and `snapshotAccess`.
   */
  def serializeSnapshot(snapshot: Snapshot): Unit =
    snapshotAccess.withOutputStream(snapshot)(snapshotSerializer.serializeSnapshot(_, snapshot, snapshot.state))

  /**
   * Deserializes and returns a snapshot using `snapshotSerializer` and `snapshotAccess`.
   */
  def deserializeSnapshot(metadata: SnapshotMetadata): Snapshot = {
    val state = snapshotAccess.withInputStream(metadata)(snapshotSerializer.deserializeSnapshot(_, metadata))
    Snapshot(metadata.processorId, metadata.sequenceNr, metadata.timestamp, state)
  }
}

/**
 * Input and output stream management for snapshot IO.
 */
trait SnapshotAccess {
  /**
   * Provides a managed output stream for writing a state object.
   *
   * @param metadata snapshot metadata needed to create an output stream.
   * @param p called with the managed output stream.
   * @throws IOException if writing fails.
   */
  def withOutputStream(metadata: SnapshotMetadata)(p: OutputStream => Unit)

  /**
   * Provides a managed input stream for reading a state object.
   *
   * @param metadata snapshot metadata needed to create an input stream.
   * @param f called with the managed input stream.
   * @return read snapshot.
   * @throws IOException if reading fails.
   */
  def withInputStream(metadata: SnapshotMetadata)(f: InputStream => Any): Any
}

/**
 * State serializer.
 */
trait SnapshotSerializer {
  /**
   * Serializes a state object to an output stream.
   */
  def serializeSnapshot(stream: OutputStream, metadata: SnapshotMetadata, state: Any): Unit
  /**
   * Deserializes a state object from an input stream.
   */
  def deserializeSnapshot(stream: InputStream, metadata: SnapshotMetadata): Any
}

object SnapshotSerializer {
  /**
   * State serializer using Java serialization.
   */
  val java = new SnapshotSerializer {
    def serializeSnapshot(stream: OutputStream, metadata: SnapshotMetadata, state: Any) =
      new ObjectOutputStream(stream).writeObject(state)

    def deserializeSnapshot(stream: InputStream, metadata: SnapshotMetadata) =
      new ClassLoaderObjectInputStream(getClass.getClassLoader, stream).readObject()
  }
}