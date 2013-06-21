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
package org.eligosource.eventsourced.journal.common.snapshot

import java.io._

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, FileSystem}

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.common.serialization._

/**
 * Snapshotting configuration object.
 */
trait HadoopFilesystemSnapshottingProps[A <: HadoopFilesystemSnapshottingProps[A]] { this: JournalProps =>
  /**
   * Path where snapshots are stored on `snapshotFilesystem`. A relative path is relative to
   * `snapshotFilesystem`'s working directory.
   */
  def snapshotPath: Path

  /**
   * Serializer for writing and reading snapshots.
   */
  def snapshotSerializer: SnapshotSerializer

  /**
   * Timeout for loading a snapshot.
   */
  def snapshotLoadTimeout: FiniteDuration

  /**
   * Timeout for saving a snapshot.
   */
  def snapshotSaveTimeout: FiniteDuration

  /**
   * Hadoop filesystem for storing snapshots.
   */
  def snapshotFilesystem: FileSystem

  /**
   * Java API.
   *
   * Returns a new `HadoopFilesystemSnapshottingProps` with specified snapshot serializer.
   */
  def withSnapshotSerializer(snapshotSerializer: SnapshotSerializer): A

  /**
   * Java API.
   *
   * Returns a new `HadoopFilesystemSnapshottingProps` with specified snapshot load timeout.
   */
  def withSnapshotLoadTimeout(snapshotLoadTimeout: FiniteDuration): A

  /**
   * Java API.
   *
   * Returns a new `HadoopFilesystemSnapshottingProps` with specified snapshot save timeout.
   */
  def withSnapshotSaveTimeout(snapshotSaveTimeout: FiniteDuration): A

  /**
   * Java API.
   *
   * Returns a new `HadoopFilesystemSnapshottingProps` with specified snapshot filesystem.
   */
  def withSnapshotFilesystem(snapshotFilesystem: FileSystem): A
}

object HadoopFilesystemSnapshotting {
  /**
   * Default local `FileSystem` for snapshot storage.
   */
  val defaultLocalFilesystem = FileSystem.getLocal(new Configuration)
}

/**
 * Hadopp `FileSystem` based snapshotting. Journal actors (i.e. actors that either extend
 * [[org.eligosource.eventsourced.journal.common.support.SynchronousWriteReplaySupport]] or
 * [[org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport]])
 * can implement snapshotting by extending this trait and call `initSnapshotting()` in their
 * `start()` method. The snapshotting configuration is specified by `props`.
 *
 * @see [[org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshottingProps]]
 */
trait HadoopFilesystemSnapshotting { this: Actor =>
  /**
   * Snapshotting configuration object.
   */
  val props: HadoopFilesystemSnapshottingProps[_]

  import props._

  private val FilenamePattern = """^snapshot-(\d+)-(\d+)-(\d+)""".r
  private var snapshotMetadata = Map.empty[Int, SortedSet[SnapshotMetadata]]

  private lazy val snapshotSerialization = new SnapshotSerialization {
    val snapshotAccess = new HadoopFilesystemSnapshotAccess(snapshotPath, snapshotFilesystem)
    val snapshotSerializer = props.snapshotSerializer
  }

  private def createSnapshotter =
    context.actorOf(Props(new SnapshotIO(snapshotMetadata)).withDispatcher("eventsourced.journal.snapshot-dispatcher"))

  /**
   * @see [[org.eligosource.eventsourced.journal.common.support.SynchronousWriteReplaySupport.loadSnapshotSync()]]
   */
  def loadSnapshotSync(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Option[Snapshot] =
    Await.result(loadSnapshot(processorId, snapshotFilter), snapshotLoadTimeout)

  /**
   * @see [[org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport.Snapshotter.loadSnapshot()]]
   */
  def loadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Future[Option[Snapshot]] =
    createSnapshotter.ask(SnapshotIO.LoadSnapshot(processorId, snapshotFilter))(Timeout(snapshotLoadTimeout)).mapTo[Option[Snapshot]]

  /**
   * @see [[org.eligosource.eventsourced.journal.common.support.SynchronousWriteReplaySupport.loadSnapshotSync()]]
   * @see [[org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport.Snapshotter.loadSnapshot()]]
   */
  def saveSnapshot(snapshot: Snapshot): Future[SnapshotSaved] =
    createSnapshotter.ask(SnapshotIO.SaveSnapshot(snapshot))(Timeout(snapshotSaveTimeout)).mapTo[SnapshotSaved]

  /**
   * @see [[org.eligosource.eventsourced.journal.common.support.SynchronousWriteReplaySupport.snapshotSaved()]]
   * @see [[org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport.Snapshotter.snapshotSaved()]]
   */
  def snapshotSaved(metadata: SnapshotMetadata) {
    snapshotMetadata.get(metadata.processorId) match {
      case None      => snapshotMetadata = snapshotMetadata + (metadata.processorId -> SortedSet(metadata))
      case Some(mds) => snapshotMetadata = snapshotMetadata + (metadata.processorId -> (mds + metadata))
    }
  }

  /**
   * Initializes snapshotting. Must be called in the implementing journal's `start()` method.
   */
  def initSnapshotting() {
    if (!snapshotFilesystem.exists(snapshotPath)) snapshotFilesystem.mkdirs(snapshotPath)

    val metadata = snapshotFilesystem.listStatus(snapshotPath).map(_.getPath.getName).collect {
      case FilenamePattern(pid, snr, tms) => SnapshotSaved(pid.toInt, snr.toLong, tms.toLong)
    }

    snapshotMetadata = SortedSet.empty[SnapshotMetadata] ++ metadata groupBy(_.processorId)
  }

  private class SnapshotIO(snapshotMetadata: Map[Int, SortedSet[SnapshotMetadata]]) extends Actor {
    import SnapshotIO._

    def receive = {
      case SaveSnapshot(s) => {
        Try(snapshotSerialization.serializeSnapshot(s)) match {
          case Success(_) => sender ! SnapshotSaved(s.processorId, s.sequenceNr, s.timestamp)
          case Failure(e) => sender ! Status.Failure(e)
        }
        context.stop(self)
      }
      case LoadSnapshot(processorId, snapshotFilter) => {
        sender ! loadSnapshot(processorId, snapshotFilter)
        context.stop(self)
      }
    }

    def loadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Option[Snapshot] = {
      @tailrec
      def go(metadata: SortedSet[SnapshotMetadata]): Option[Snapshot] = metadata.lastOption match {
        case None     => None
        case Some(md) => {
          Try(snapshotSerialization.deserializeSnapshot(md)) match {
            case Success(ss) => Some(ss)
            case Failure(_)  => go(metadata.init) // try older snapshot
          }
        }
      }

      for {
        mds <- snapshotMetadata.get(processorId)
        md  <- go(mds.filter(snapshotFilter))
      } yield md
    }
  }

  private object SnapshotIO {
    case class SaveSnapshot(s: Snapshot)
    case class LoadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean)
  }
}

private [journal] class HadoopFilesystemSnapshotAccess(snapshotPath: Path, snapshotFilesystem: FileSystem) extends SnapshotAccess {
  def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) => Unit) =
    withStream(snapshotFilesystem.create(snapshotFile(metadata)), p)

  def withInputStream(metadata: SnapshotMetadata)(p: (InputStream) => Any) =
    withStream(snapshotFilesystem.open(snapshotFile(metadata)), p)

  private def withStream[A <: Closeable, B](stream: A, p: A => B): B =
    try { p(stream) } finally { stream.close() }

  private def snapshotFile(metadata: SnapshotMetadata): Path =
    new Path(snapshotPath, s"snapshot-${metadata.processorId}-${metadata.sequenceNr}-${metadata.timestamp}")
}

