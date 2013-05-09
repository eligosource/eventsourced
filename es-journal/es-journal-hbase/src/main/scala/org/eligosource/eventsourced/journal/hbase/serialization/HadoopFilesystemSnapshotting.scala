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
package org.eligosource.eventsourced.journal.hbase.serialization

import java.io._

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.apache.hadoop.fs.{Path, FileSystem}

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common.serialization._

private [journal] trait HadoopFilesystemSnapshotting { outer: Actor =>
  private val FilenamePattern = """^snapshot-(\d+)-(\d+)-(\d+)""".r
  private var snapshotMetadata = Map.empty[Int, SortedSet[SnapshotMetadata]]

  lazy val snapshotSerialization = new SnapshotSerialization {
    val snapshotAccess = new HadoopFilesystemSnapshotAccess(snapshotDir, snapshotFilesystem)
    val snapshotSerializer = outer.snapshotSerializer
  }

  def snapshotDir: Path
  def snapshotFilesystem: FileSystem
  def snapshotSerializer: SnapshotSerializer

  def snapshotSaveTimeout: FiniteDuration
  def snapshotLoadTimeout: FiniteDuration

  def createSnapshotter =
    context.actorOf(Props(new SnapshotIO(snapshotMetadata)).withDispatcher("eventsourced.journal.snapshot-dispatcher"))

  def loadSnapshot(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Future[Option[Snapshot]] =
    createSnapshotter.ask(SnapshotIO.LoadSnapshot(processorId, snapshotFilter))(Timeout(snapshotLoadTimeout)).mapTo[Option[Snapshot]]

  def saveSnapshot(snapshot: Snapshot): Future[SnapshotSaved] =
    createSnapshotter.ask(SnapshotIO.SaveSnapshot(snapshot))(Timeout(snapshotSaveTimeout)).mapTo[SnapshotSaved]

  def snapshotSaved(metadata: SnapshotMetadata) {
    snapshotMetadata.get(metadata.processorId) match {
      case None      => snapshotMetadata = snapshotMetadata + (metadata.processorId -> SortedSet(metadata))
      case Some(mds) => snapshotMetadata = snapshotMetadata + (metadata.processorId -> (mds + metadata))
    }
  }

  def initSnapshotting() {
    if (!snapshotFilesystem.exists(snapshotDir)) snapshotFilesystem.mkdirs(snapshotDir)

    val metadata = snapshotFilesystem.listStatus(snapshotDir).map(_.getPath.getName).collect {
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

private [hbase] class HadoopFilesystemSnapshotAccess(snapshotDir: Path, snapshotFilesystem: FileSystem) extends SnapshotAccess {
  def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) => Unit) =
    withStream(snapshotFilesystem.create(snapshotFile(metadata)), p)

  def withInputStream(metadata: SnapshotMetadata)(p: (InputStream) => Any) =
    withStream(snapshotFilesystem.open(snapshotFile(metadata)), p)

  private def withStream[A <: Closeable, B](stream: A, p: A => B): B =
    try { p(stream) } finally { stream.close() }

  private def snapshotFile(metadata: SnapshotMetadata): Path =
    new Path(snapshotDir, s"snapshot-${metadata.processorId}-${metadata.sequenceNr}-${metadata.timestamp}")
}

