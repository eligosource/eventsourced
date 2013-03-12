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

import scala.concurrent._

import akka.actor._

import org.apache.hadoop.hbase.util.Bytes
import org.hbase.async.{KeyValue, DeleteRequest, HBaseClient, PutRequest}

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.journal.common.AsynchronousWriteReplaySupport

/**
 * HBase journal with asynchronous, non-blocking IO.
 *
 * <strong>EXPERIMENTAL</strong>.
 */
private [hbase] class AsyncHBaseJournal(props: AsyncHBaseJournalProps) extends AsynchronousWriteReplaySupport {
  import context.dispatcher

  val serialization = Serialization(context.system)
  var client: HBaseClient = _

  def journalProps = props

  def asyncWriteTimeout = props.asyncWriteTimeout
  def asyncWriterCount = props.asyncWriterCount

  def writer(id: Int) = new Writer {

    def executeWriteInMsg(cmd: WriteInMsg): Future[Any] = {
      //println("writing in  key " + bitString(InMsgKey(0, cmd.processorId, cmd.message.sequenceNr)))
      val key = keyToBytes(InMsgKey(0, cmd.processorId, cmd.message.sequenceNr))
      val msg = serialization.serializeMessage(cmd.message.clearConfirmationSettings)
      val put = new PutRequest(TableNameBytes, key, ColumnFamilyNameBytes, MsgColumnNameBytes, msg)
      val wrt = client.put(put); client.flush()
      wrt.map(_ => ())
    }

    def executeWriteOutMsg(cmd: WriteOutMsg): Future[Any] = {
      //println("writing out key " + bitString(OutMsgKey(0, cmd.channelId, cmd.message.sequenceNr)))
      val key = keyToBytes(OutMsgKey(0, cmd.channelId, cmd.message.sequenceNr))
      val msg = serialization.serializeMessage(cmd.message.clearConfirmationSettings)
      val put = new PutRequest(TableNameBytes, key, ColumnFamilyNameBytes, MsgColumnNameBytes, msg)
      val outFuture: Future[Any] = client.put(put)
      val ackFuture: Future[Any] = if (cmd.ackSequenceNr != SkipAck) {
        //println("writing ack key " + byteString(InMsgKey(0, cmd.ackProcessorId, cmd.ackSequenceNr)))
        val key = keyToBytes(InMsgKey(0, cmd.ackProcessorId, cmd.ackSequenceNr))
        val put = new PutRequest(TableNameBytes, key, ColumnFamilyNameBytes, ackColumnBytes(cmd.channelId), Bytes.toBytes(cmd.channelId))
        client.put(put)
      } else Future.successful(())
      client.flush()
      val wrt = for {
        _ <- outFuture
        a <- ackFuture
      } yield a
      wrt.map(_ => ())
    }

    def executeWriteAck(cmd: WriteAck): Future[Any] = {
      val key = keyToBytes(InMsgKey(0, cmd.processorId, cmd.ackSequenceNr))
      val put = new PutRequest(TableNameBytes, key, ColumnFamilyNameBytes, ackColumnBytes(cmd.channelId), Bytes.toBytes(cmd.channelId))
      val wrt = client.put(put); client.flush()
      wrt.map(_ => ())
    }

    def executeDeleteOutMsg(cmd: DeleteOutMsg): Future[Any] = {
      val key = keyToBytes(OutMsgKey(0, cmd.channelId, cmd.msgSequenceNr))
      val del = new DeleteRequest(TableNameBytes, key)
      val wrt = client.delete(del); client.flush()
      wrt.map(_ => ())
    }
  }

  def replayer = new Replayer {
    import scala.collection.JavaConverters._

    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sdr: ActorRef, toSequenceNr: Long): Future[Any] = {
      Future.sequence(cmds.map(cmd => replay(
        InMsgKey(0, cmd.processorId, cmd.fromSequenceNr),
        InMsgKey(0, cmd.processorId, toSequenceNr + 1), msg => p(msg, cmd.target)))) andThen { case _ => sdr ! ReplayDone }
    }

    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sdr: ActorRef, toSequenceNr: Long): Future[Any] = {
      replay(
        InMsgKey(0, cmd.processorId, cmd.fromSequenceNr),
        InMsgKey(0, cmd.processorId, toSequenceNr + 1), p) andThen { case _ => sdr ! ReplayDone }
    }

    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sdr: ActorRef, toSequenceNr: Long): Future[Any] = {
      replay(
        OutMsgKey(0, cmd.channelId, cmd.fromSequenceNr),
        OutMsgKey(0, cmd.channelId, toSequenceNr + 1), p)
    }

    def replay[T : KeyRepresentation](startKey: T, stopKey: T, p: (Message) => Unit): Future[Any] = {
      //println("replay from key " + bitString(startKey))
      //println("replay to   key " + bitString(stopKey))
      val scanner = client.newScanner(TableNameBytes)

      def replay(): Future[Any] = deferredToFuture(scanner.nextRows()).flatMap { rows =>
        if (rows eq null) {
          scanner.close()
          Future.successful(())
        } else {
          for {
            row <- rows.asScala
            msg <- rowToMessage(row.asScala)
          } p(msg)
          replay()
        }
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

      scanner.setFamily(ColumnFamilyNameBytes)
      scanner.setStartKey(keyToBytes(startKey))
      scanner.setStopKey(keyToBytes(stopKey))

      replay()
    }
  }

  def storedCounter = 0L // TODO: implement

  override def start() {
    client = new HBaseClient(props.zookeeperQuorumSpec)
  }

  override def stop() {
    client.shutdown()
  }
}
