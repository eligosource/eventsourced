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
package org.eligosource.eventsourced.journal.common

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
    snapshotAccess.withOutputStream(snapshot)(snapshotSerializer.serializeSnapshot(_, snapshot))

  /**
   * Deserializes and returns a snapshot using `snapshotSerializer` and `snapshotAccess`.
   */
  def deserializeSnapshot(metadata: SnapshotMetadata): Snapshot =
    snapshotAccess.withInputStream(metadata)(snapshotSerializer.deserializeSnapshot)
}

/**
 * Input and output stream management for snapshot IO.
 */
trait SnapshotAccess {
  /**
   * Provides a managed output stream for writing snapshots.
   *
   * @param metadata snapshot metadata needed to create an output stream.
   * @param p called with the managed output stream.
   * @throws IOException if writing fails.
   */
  def withOutputStream(metadata: SnapshotMetadata)(p: OutputStream => Unit)

  /**
   * Provides a managed input stream for reading snapshots.
   *
   * @param metadata snapshot metadata needed to create an input stream.
   * @param f called with the managed input stream.
   * @return read snapshot.
   * @throws IOException if reading fails.
   */
  def withInputStream(metadata: SnapshotMetadata)(f: InputStream => Snapshot): Snapshot
}

/**
 * Snapshot serializer.
 */
trait SnapshotSerializer {
  /**
   * Serializes a snapshot to an output stream.
   */
  def serializeSnapshot(stream: OutputStream, snapshot: Snapshot): Unit
  /**
   * Deserializes a snapshot from an input stream.
   */
  def deserializeSnapshot(stream: InputStream): Snapshot
}

object SnapshotSerializer {
  /**
   * Snapshot serializer using Java serialization.
   */
  val java = new SnapshotSerializer {
    def serializeSnapshot(stream: OutputStream, snapshot: Snapshot) =
      new ObjectOutputStream(stream).writeObject(snapshot)

    def deserializeSnapshot(stream: InputStream) =
      new ClassLoaderObjectInputStream(getClass.getClassLoader, stream).readObject().asInstanceOf[Snapshot]
  }
}