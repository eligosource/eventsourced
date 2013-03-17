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
package org.eligosource.eventsourced.journal.mongodb.casbah

import akka.actor._

import com.mongodb.casbah.Imports._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.common._
import java.util.Calendar

private [eventsourced] class MongodbJournal(props: MongodbJournalProps) extends SynchronousWriteReplaySupport {
  import Journal._

  val serialization = Serialization(context.system)

  implicit def msgToBytes(msg: Message): Array[Byte] = serialization.serializeMessage(msg)
  implicit def msgFromBytes(bytes: Array[Byte]): Message = serialization.deserializeMessage(bytes)

  def executeWriteInMsg(cmd: WriteInMsg) {
    val built = createMessageCollection(cmd.processorId, 0, counter, 0, msgToBytes(cmd.message.clearConfirmationSettings))
    props.journalColl.insert(built, WriteConcern.JournalSafe)
  }

  def executeWriteOutMsg(cmd: WriteOutMsg) {

    val built = createMessageCollection(Int.MaxValue, cmd.channelId, counter, 0, msgToBytes(cmd.message.clearConfirmationSettings))

    props.journalColl.insert(built)

    if (cmd.ackSequenceNr != SkipAck) {
      val built = createMessageCollection(cmd.ackProcessorId, 0, cmd.ackSequenceNr, cmd.channelId, Array.empty[Byte])
      props.journalColl.insert(built)
    }
  }

  def executeWriteAck(cmd: WriteAck) {
    val built = createMessageCollection(cmd.processorId, 0, cmd.ackSequenceNr, cmd.channelId, Array.empty[Byte])
    props.journalColl.insert(built)
  }

  def executeDeleteOutMsg(cmd: DeleteOutMsg) {
    props.journalColl.remove(MongoDBObject(
      "processorId"         -> Int.MaxValue,
      "initiatingChannelId" -> cmd.channelId,
      "sequenceNr"          -> cmd.msgSequenceNr,
      "confirmingChannelId" -> 0))
  }

  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => replay(cmd.processorId, 0, cmd.fromSequenceNr, msg => p(msg, cmd.target)))
    sender ! ReplayDone
  }

  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit) {
    replay(cmd.processorId, 0, cmd.fromSequenceNr, p)
    sender ! ReplayDone
  }

  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit) {
    replay(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr, p)
  }

  def storedCounter = {
    val cursor = props.journalColl.find().sort(MongoDBObject("sequenceNr" -> -1)).limit(1)
    if (cursor.hasNext) cursor.next().getAs[Long]("sequenceNr").get else 0L
  }

  private def replay(processorId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit) {

    val query = MongoDBObject(
      "processorId"         -> processorId,
      "initiatingChannelId" -> channelId,
      "sequenceNr"          -> MongoDBObject("$gte" -> fromSequenceNr))

    val sortKey = MongoDBObject(
      "processorId"         -> 1,
      "initiatingChannelId" -> 1,
      "sequenceNr"          -> 1,
      "confirmingChannelId" -> 1)

    val startKey = Key(processorId, channelId, fromSequenceNr, 0)
    val cursor = props.journalColl.find(query).sort(sortKey)
    val iter = cursor.toIterator.buffered

    replay(iter, startKey, p)
  }

  @scala.annotation.tailrec
  private def replay(iter: BufferedIterator[DBObject], key: Key, p: Message => Unit) {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = createKey(nextEntry)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey, p)
      } else if (key.processorId         == nextKey.processorId &&
                 key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = msgFromBytes(nextEntry.getAs[Array[Byte]]("message").get)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: BufferedIterator[DBObject], key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.head
      val nextKey = createKey(nextEntry)
      if (key.processorId         == nextKey.processorId &&
          key.initiatingChannelId == nextKey.initiatingChannelId &&
          key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }

  private def createMessageCollection(processorId: Int, initiatingChannelId: Int, sequenceNr: Long,
                                      confirmingChannelId: Int, msgAsBytes: Array[Byte]) = {
    val builder = MongoDBObject.newBuilder
    builder += "processorId"         -> processorId
    builder += "initiatingChannelId" -> initiatingChannelId
    builder += "sequenceNr"          -> sequenceNr
    builder += "confirmingChannelId" -> confirmingChannelId
    builder += "message"             -> msgAsBytes
    builder.result
  }

  private def createKey(dbObject: DBObject) = {
    Key(
      dbObject.getAs[Int]("processorId").get,
      dbObject.getAs[Int]("initiatingChannelId").get,
      dbObject.getAs[Long]("sequenceNr").get,
      dbObject.getAs[Int]("confirmingChannelId").get)
  }
}
