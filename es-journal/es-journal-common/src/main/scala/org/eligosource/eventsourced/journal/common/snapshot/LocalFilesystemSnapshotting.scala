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
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

import akka.actor._
import akka.pattern.ask
import akka.util._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common.serialization._

private [journal] trait LocalFilesystemSnapshotting { outer: Actor =>
  private val FilenamePattern = """^snapshot-(\d+)-(\d+)-(\d+)""".r
  private var snapshotMetadata = Map.empty[Int, SortedSet[SnapshotMetadata]]

  lazy val snapshotSerialization = new SnapshotSerialization {
    val snapshotAccess = new LocalFilesystemSnapshotAccess(snapshotDir)
    val snapshotSerializer = outer.snapshotSerializer
  }

  def snapshotDir: File
  def snapshotSerializer: SnapshotSerializer
  def snapshotSaveTimeout: FiniteDuration

  def loadSnapshotSync(processorId: Int, snapshotFilter: SnapshotMetadata => Boolean): Option[Snapshot] = {
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

  def saveSnapshot(snapshot: Snapshot): Future[SnapshotSaved] = {
    val snapshotter = context.actorOf(Props(new Snapshotter)
      .withDispatcher("eventsourced.journal.snapshot-dispatcher"))
    snapshotter.ask(snapshot)(Timeout(snapshotSaveTimeout)).mapTo[SnapshotSaved]
  }

  def snapshotSaved(metadata: SnapshotMetadata) {
    snapshotMetadata.get(metadata.processorId) match {
      case None      => snapshotMetadata = snapshotMetadata + (metadata.processorId -> SortedSet(metadata))
      case Some(mds) => snapshotMetadata = snapshotMetadata + (metadata.processorId -> (mds + metadata))
    }
  }

  def initSnapshotting() {
    if (!snapshotDir.exists) snapshotDir.mkdirs()

    val metadata = snapshotDir.listFiles.map(_.getName).collect {
      case FilenamePattern(pid, snr, tms) => SnapshotSaved(pid.toInt, snr.toLong, tms.toLong)
    }

    snapshotMetadata = SortedSet.empty[SnapshotMetadata] ++ metadata groupBy(_.processorId)
  }

  private class Snapshotter extends Actor {
    def receive = {
      case s: Snapshot => {
        Try(snapshotSerialization.serializeSnapshot(s)) match {
          case Success(_) => sender ! SnapshotSaved(s.processorId, s.sequenceNr, s.timestamp)
          case Failure(e) => sender ! Status.Failure(e)
        }
        context.stop(self)
      }
    }
  }
}

private [journal] class LocalFilesystemSnapshotAccess(snapshotDir: File) extends SnapshotAccess {
  def withOutputStream(metadata: SnapshotMetadata)(p: (OutputStream) => Unit) =
    withStream(new FileOutputStream(snapshotFile(metadata)), p)

  def withInputStream(metadata: SnapshotMetadata)(p: (InputStream) => Any) =
    withStream(new FileInputStream(snapshotFile(metadata)), p)

  private def withStream[A <: Closeable, B](stream: A, p: A => B): B =
    try { p(stream) } finally { stream.close() }

  private def snapshotFile(metadata: SnapshotMetadata): File =
    new File(snapshotDir, s"snapshot-${metadata.processorId}-${metadata.sequenceNr}-${metadata.timestamp}")
}

