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

import akka.actor._

import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.util.Bytes

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.journal.common.SynchronousWriteReplaySupport

/**
 * HBase journal with synchronous IO.
 *
 * <strong>EXPERIMENTAL</strong>.
 */
private [hbase] class SyncHBaseJournal(props: SyncHBaseJournalProps) extends SynchronousWriteReplaySupport {
  import scala.collection.JavaConverters._

  val serialization = Serialization(context.system)

  var client: HTable = _

  def executeWriteInMsg(cmd: WriteInMsg) {
    //println("writing in  key " + bitString(InMsgKey(0, cmd.processorId, cmd.message.sequenceNr)))
    val put = new Put(InMsgKey(0, cmd.processorId, cmd.message.sequenceNr))
    put.add(ColumnFamilyNameBytes, MsgColumnNameBytes, serialization.serializeMessage(cmd.message.clearConfirmationSettings))
    client.put(put)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg) {
    //println("writing out key " + bitString(OutMsgKey(0, cmd.channelId, cmd.message.sequenceNr)))
    val puts = new java.util.ArrayList[Put](2)
    val putOut = new Put(OutMsgKey(0, cmd.channelId, cmd.message.sequenceNr))
    putOut.add(ColumnFamilyNameBytes, MsgColumnNameBytes, serialization.serializeMessage(cmd.message.clearConfirmationSettings))
    puts.add(putOut)

    if (cmd.ackSequenceNr != SkipAck) {
      //println("writing ack key " + byteString(InMsgKey(0, cmd.ackProcessorId, cmd.ackSequenceNr)))
      val putAck = new Put(InMsgKey(0, cmd.ackProcessorId, cmd.ackSequenceNr))
      putAck.add(ColumnFamilyNameBytes, ackColumnBytes(cmd.channelId), Bytes.toBytes(cmd.channelId))
      puts.add(putAck)
    }
    client.put(puts)
  }

  def executeWriteAck(cmd: WriteAck) {
    val put = new Put(InMsgKey(0, cmd.processorId, cmd.ackSequenceNr))
    put.add(ColumnFamilyNameBytes, ackColumnBytes(cmd.channelId), Bytes.toBytes(cmd.channelId))
    client.put(put)
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    val del = new Delete(OutMsgKey(0, cmd.channelId, cmd.msgSequenceNr))
    client.delete(del)
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => replay(
      InMsgKey(0, cmd.processorId, cmd.fromSequenceNr),
      InMsgKey(0, cmd.processorId, Long.MaxValue), msg => p(msg, cmd.target)))
    sender ! ReplayDone
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit) {
    replay(
      InMsgKey(0, cmd.processorId, cmd.fromSequenceNr),
      InMsgKey(0, cmd.processorId, Long.MaxValue), p)
    sender ! ReplayDone
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit) {
    replay(
      OutMsgKey(0, cmd.channelId, cmd.fromSequenceNr),
      OutMsgKey(0, cmd.channelId, Long.MaxValue), p)
  }

  def replay[T : KeyRepresentation](startKey: T, stopKey: T, p: (Message) => Unit) {
    //println("replay from key " + bitString(startKey))
    //println("replay to   key " + bitString(stopKey))

    val scan = new Scan()
    scan.addFamily(ColumnFamilyNameBytes)
    scan.setStartRow(startKey)
    scan.setStopRow(stopKey)

    val scanner = client.getScanner(scan)
    scanner.asScala foreach { r =>
      val msgBytes = r.getValue(ColumnFamilyNameBytes, MsgColumnNameBytes)
      if (msgBytes ne null) {
        val msg = serialization.deserializeMessage(msgBytes)
        val acks = r.list.asScala
          .map(kv => new String(kv.getQualifier)).filter(_.startsWith("a"))
          .map(aq => aq.substring(AckColumnPrefix.length).toInt)
        p(msg.copy(acks = acks))
      } else { /* phantom ack, ignore */ }
    }
    scanner.close()
  }

  // TODO: derive counter from stored event messages
  def storedCounter = 0L

  override def start() {
    client = new HTable(props.configuration, TableName)
  }

  override def stop() {
    client.close()
  }
}
