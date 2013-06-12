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
package org.eligosource.eventsourced.journal.hbase

import scala.collection.JavaConverters._
import scala.concurrent._
import scala.util._

import akka.actor._

import org.apache.hadoop.hbase.util.Bytes
import org.hbase.async.{KeyValue, DeleteRequest, HBaseClient, PutRequest}

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.journal.common.serialization._
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting
import org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport
import org.eligosource.eventsourced.journal.common.util._

/**
 * HBase journal with asynchronous, non-blocking IO and concurrent reads/writes.
 */
private [hbase] class HBaseJournal(val props: HBaseJournalProps) extends AsynchronousWriteReplaySupport with HadoopFilesystemSnapshotting { outer =>
  import context.dispatcher

  val serialization = MessageSerialization(context.system)
  val tableNameBytes = Bytes.toBytes(props.tableName)

  var client: HBaseClient = _
  var partitionCount: Int = _

  def journalProps = props
  def partition(snr: Long) = snr % partitionCount toInt

  def snapshotter = new Snapshotter {
    def loadSnapshot(processorId: Int, snapshotFilter: (SnapshotMetadata) => Boolean) =
      outer.loadSnapshot(processorId, snapshotFilter)

    def saveSnapshot(snapshot: Snapshot) =
      outer.saveSnapshot(snapshot)

    def snapshotSaved(metadata: SnapshotMetadata) =
      outer.snapshotSaved(metadata)
  }

  def writer = new Writer {
    def executeWriteInMsg(cmd: WriteInMsg): Future[Any] = {
      val snr = cmd.message.sequenceNr
      val prt = partition(snr)
      val key = InMsgKey(prt, cmd.processorId, snr).toBytes
      val msg = serialization.serializeMessage(cmd.message.clearConfirmationSettings)
      val ftr = executeWriteMsg(prt, snr, key, msg).map(_ => ())
      client.flush()
      ftr
    }

    def executeWriteOutMsg(cmd: WriteOutMsg): Future[Any] = {
      val snr = cmd.message.sequenceNr
      val prt = partition(snr)
      val key = OutMsgKey(prt, cmd.channelId, snr).toBytes
      val msg = serialization.serializeMessage(cmd.message.clearConfirmationSettings)
      val putMsgFuture = executeWriteMsg(prt, snr, key, msg)
      val putAckFuture: Future[Any] = if (cmd.ackSequenceNr != SkipAck) {
        val snr = cmd.ackSequenceNr
        val key = InMsgKey(partition(snr), cmd.ackProcessorId, snr).toBytes
        val putAck = new PutRequest(tableNameBytes, key, ColumnFamilyNameBytes, ackColumnBytes(cmd.channelId), Bytes.toBytes(cmd.channelId))
        val putFtr = client.put(putAck)
        putMsgFuture.flatMap(_ => putFtr)
      } else putMsgFuture
      val wrt = for {
        _ <- putMsgFuture
        a <- putAckFuture
      } yield a
      client.flush()
      wrt.map(_ => ())
    }

    def executeWriteMsg(prt: Int, snr: Long, key: Array[Byte], msg: Array[Byte]): Future[Any] = {
      // EXPERIMENTAL
      //
      // - write lower counter limit every 256 messages (per partition)
      // - needed for efficient counter value recovery during journal start
      //
      val lmt = 256
      //
      // - TODO: make configurable
      //

      val putMsg = new PutRequest(tableNameBytes, key, ColumnFamilyNameBytes, MsgColumnNameBytes, msg)
      val putCtr = new PutRequest(tableNameBytes, CounterKey(prt, snr).toBytes, ColumnFamilyNameBytes, SequenceNrColumnNameBytes, longToBytes(snr))
      val putMsgFuture: Future[Any] = client.put(putMsg)
      val putCtrFuture: Future[Any] = client.put(putCtr)
      val putLmtFuture: Future[Any] = if ((snr >= lmt) && (snr - prt) % lmt == 0) {
        val putLmt = new PutRequest(tableNameBytes, CounterKey(prt, 0L).toBytes, ColumnFamilyNameBytes, SequenceNrColumnNameBytes, longToBytes(snr))
        client.put(putLmt)
      } else putCtrFuture
      for {
        _ <- putMsgFuture
        _ <- putCtrFuture
        a <- putLmtFuture
      } yield a
    }

    def executeWriteAck(cmd: WriteAck): Future[Any] = {
      val snr = cmd.ackSequenceNr
      val key = InMsgKey(partition(snr), cmd.processorId, snr).toBytes
      val put = new PutRequest(tableNameBytes, key, ColumnFamilyNameBytes, ackColumnBytes(cmd.channelId), Bytes.toBytes(cmd.channelId))
      val wrt = client.put(put); client.flush()
      wrt.map(_ => ())
    }

    def executeDeleteOutMsg(cmd: DeleteOutMsg): Future[Any] = {
      val snr = cmd.msgSequenceNr
      val key = OutMsgKey(partition(snr), cmd.channelId, snr).toBytes
      val del = new DeleteRequest(tableNameBytes, key)
      val wrt = client.delete(del); client.flush()
      wrt.map(_ => ())
    }
  }

  def replayer = new Replayer {
    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sdr: ActorRef): Future[Any] = {
      Future.sequence[Any, Seq](cmds.map(cmd => scan(
        InMsgKey(-1, cmd.processorId, cmd.fromSequenceNr),
        InMsgKey(-1, cmd.processorId, cmd.toSequenceNr), msg => p(msg, cmd.target))))
    }

    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sdr: ActorRef): Future[Any] = {
      scan(
        InMsgKey(-1, cmd.processorId, cmd.fromSequenceNr),
        InMsgKey(-1, cmd.processorId, cmd.toSequenceNr), p)
    }

    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sdr: ActorRef): Future[Any] = {
      scan(
        OutMsgKey(-1, cmd.channelId, cmd.fromSequenceNr),
        OutMsgKey(-1, cmd.channelId, cmd.toSequenceNr), p)
    }

    def scan(startKey: Key, stopKey: Key, p: (Message) => Unit): Future[Any] = {
      val chunkSize = props.replayChunkSize
      val promise = Promise[Any]

      def scanPartitions(startKey: Key, stopKey: Key) = {
        def scanPartition(partition: Int) = scanLow(
          startKey.withPartition(partition),
          stopKey.withPartition(partition), p)
        Future.sequence(0 until partitionCount map(scanPartition)).map(_.flatten.sortBy(_.sequenceNr))
      }

      def go(startKey: Key, stopKey: Key) {
        val from = startKey.sequenceNumber
        val to = stopKey.sequenceNumber
        if (from > to) promise.success(())
        else if ((to - from) < chunkSize ) scanPartitions(startKey, stopKey) onComplete {
          case Success(s) => { s.foreach(p); promise.success(()) }
          case Failure(e) => promise.failure(e)
        }
        else scanPartitions(startKey, startKey.withSequenceNumber(_ + chunkSize)) onComplete {
          case Success(s) => { s.foreach(p); go(startKey.withSequenceNumber(_ + chunkSize + 1), stopKey) }
          case Failure(e) => promise.failure(e)
        }
      }

      go(startKey, stopKey)
      promise.future
    }

    def scanLow(startKey: Key, stopKey: Key, p: (Message) => Unit): Future[Seq[Message]] = {
      val scanner = client.newScanner(tableNameBytes)

      scanner.setFamily(ColumnFamilyNameBytes)
      scanner.setStartKey(startKey.toBytes)
      scanner.setStopKey(stopKey.withSequenceNumber(_ + 1).toBytes)

      def go(): Future[Seq[Message]] = {
        deferredToFuture(scanner.nextRows()).flatMap { rows =>
          rows match {
            case null => {
              scanner.close()
              Future.successful(Nil)
            }
            case _ => {
              val msgs = for {
                row <- rows.asScala
                msg <- rowToMessage(row.asScala)
              } yield msg
              go().map(msgs ++ _)
            }
          }
        }
      }
      go()
    }

    def rowToMessage(row: Seq[KeyValue]): Option[Message] = {
      var msgo: Option[Message] = None
      var acks: List[Int] = Nil

      row foreach { kv =>
        new String(kv.qualifier) match {
          case MsgColumnName => {
            msgo = Some(serialization.deserializeMessage(kv.value()))
          }
          case anyColumnName => if (anyColumnName.startsWith(AckColumnPrefix)) {
            val ack = anyColumnName.substring(AckColumnPrefix.length).toInt
            acks = ack :: acks
          }
        }
      }

      msgo.map(_.copy(acks = acks))
    }
  }

  def storedCounter = {
    def storedCounterP(partition: Int, start: Long, stop: Long): Future[Long] = {
      val scanner = client.newScanner(tableNameBytes)

      scanner.setFamily(ColumnFamilyNameBytes)
      scanner.setQualifier(SequenceNrColumnNameBytes)
      scanner.setStartKey(CounterKey(partition, start).toBytes)
      scanner.setStopKey(CounterKey(partition, stop).toBytes)

      def go(): Future[Long] = {
        deferredToFuture(scanner.nextRows()).flatMap { rows =>
          rows match {
            case null => {
              scanner.close()
              Future.successful(start)
            }
            case _ => {
              val vals = for {
                a <- rows.asScala
                b <- a.asScala
              } yield longFromBytes(b.value())
              go().map(_ max vals.max)
            }
          }
        }
      }
      go()
    }

    def storedCounter(start: Long, stop: Long): Future[Long] =
      Future.sequence(0 until partitionCount map(storedCounterP(_, start, stop))).map(_.max)

    val max = for {
      lower <- storedCounter(0L, 1L)
      upper <- storedCounter(lower, Long.MaxValue)
    } yield upper

    Await.result(max, props.initTimeout)
  }

  override def start() {
    initSnapshotting()

    client = new HBaseClient(props.zookeeperQuorum)

    val invalid = -1
    val scanner = client.newScanner(tableNameBytes)

    scanner.setFamily(ColumnFamilyNameBytes)
    scanner.setQualifier(PartitionCountColumnNameBytes)
    scanner.setStartKey(PartitionCountKey(false).toBytes)
    scanner.setStopKey(PartitionCountKey(true).toBytes)

    val pcf = deferredToFuture(scanner.nextRows(1)).map { rows =>
      rows match {
        case null => invalid
        case _ => {
          val pcs = for {
            a <- rows.asScala
            b <- a.asScala
          } yield Bytes.toInt(b.value())
          if (pcs.isEmpty) invalid else pcs.head
        }
      }
    }

    val pc = Await.result(pcf, props.initTimeout)

    if (pc == invalid) throw new PartitionCountNotFoundException(props.tableName)
    else partitionCount = pc
  }

  override def stop() {
    client.shutdown()
  }
}
