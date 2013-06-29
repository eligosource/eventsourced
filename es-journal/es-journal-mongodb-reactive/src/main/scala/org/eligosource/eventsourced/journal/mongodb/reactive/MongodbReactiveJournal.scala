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
package org.eligosource.eventsourced.journal.mongodb.reactive

import akka.actor._
import akka.event.Logging

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.journal.common.serialization._
import org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport
import org.eligosource.eventsourced.journal.common.snapshot.HadoopFilesystemSnapshotting
import org.eligosource.eventsourced.journal.common.util._

import reactivemongo.api._
import reactivemongo.api.collections.default._
import reactivemongo.api.indexes._
import reactivemongo.bson._
import reactivemongo.core.errors._

import scala.concurrent._
import scala.Some
import scala.util._
import reactivemongo.core.commands.GetLastError

import play.api.libs.iteratee.Iteratee

private [eventsourced] class MongodbReactiveJournal(val props: MongodbReactiveJournalProps) extends AsynchronousWriteReplaySupport
  with ActorLogging  with HadoopFilesystemSnapshotting { outer =>

  import context.dispatcher

  implicit val rmReader = ReactiveMessage.ReactiveMessageReader
  implicit val rmWriter = ReactiveMessage.ReactiveMessageWriter
  implicit def msgToBytes(msg: Message): Array[Byte] = serialization.serializeMessage(msg)
  implicit def msgFromBytes(bytes: Array[Byte]): Message = serialization.deserializeMessage(bytes)

  val serialization = MessageSerialization(context.system)

  var driver: MongoDriver = _
  var connection: MongoConnection = _
  var collection: BSONCollection = _

  def journalProps = props

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
      val rm = ReactiveMessage(key = Key(cmd.processorId, sequenceNr = cmd.message.sequenceNr), msgAsBytes = msgToBytes(cmd.message.clearConfirmationSettings))
      val future = collection.insert(rm, journalProps.writeConcern)
      future.onFailure { case e: DatabaseException => log.error("[MongodbReactiveJournal.executeWriteInMsg] " + e.getMessage) }
      future
    }

    def executeWriteOutMsg(cmd: WriteOutMsg): Future[Any] = {
      val rm = ReactiveMessage(key = Key(initiatingChannelId = cmd.channelId, sequenceNr = cmd.message.sequenceNr),
        msgAsBytes = msgToBytes(cmd.message.clearConfirmationSettings))
      val msgFtr = collection.insert(rm, journalProps.writeConcern)
      val ackFtr = if (cmd.ackSequenceNr != SkipAck) {
        val rm = ReactiveMessage(key = Key(cmd.ackProcessorId, sequenceNr = cmd.ackSequenceNr, confirmingChannelId = cmd.channelId))
        val future = collection.insert(rm, journalProps.writeConcern)
        future.onFailure { case e: DatabaseException => log.error("[MongodbReactiveJournal.executeWriteOutMsg] " + e.getMessage) }
        future
      } else msgFtr
      val wrtFtr = for { _ <- msgFtr; a <- ackFtr } yield a
      wrtFtr
    }

    def executeWriteAck(cmd: WriteAck): Future[Any] = {
      val rm = ReactiveMessage(key = Key(cmd.processorId, sequenceNr = cmd.ackSequenceNr, confirmingChannelId = cmd.channelId))
      val future = collection.insert(rm, journalProps.writeConcern)
      future.onFailure { case e: DatabaseException => log.error("[MongodbReactiveJournal.executeWriteAck] " + e.getMessage) }
      future
    }

    def executeDeleteOutMsg(cmd: DeleteOutMsg): Future[Any] = {
      val qry = BSONDocument(
        "processorId"         -> BSONInteger(Int.MaxValue),
        "initiatingChannelId" -> BSONInteger(cmd.channelId),
        "sequenceNr"          -> BSONLong(cmd.msgSequenceNr),
        "confirmingChannelId" -> BSONInteger(0))
      val future = collection.remove(qry, journalProps.writeConcern)
      future.onFailure { case e: DatabaseException => log.error("[MongodbReactiveJournal.executeDeleteOutMsg] " + e.getMessage) }
      future
    }
  }

  def replayer = new Replayer {

    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sdr: ActorRef): Future[Any] = {
      Future.sequence[Any, Seq](cmds.map(cmd => replay(
        Key(cmd.processorId, sequenceNr = cmd.fromSequenceNr),
        Key(cmd.processorId, sequenceNr = cmd.toSequenceNr),
        msg => p(msg, cmd.target))))
    }

    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sdr: ActorRef): Future[Any] = {
      replay(
        Key(cmd.processorId, sequenceNr = cmd.fromSequenceNr),
        Key(cmd.processorId, sequenceNr = cmd.toSequenceNr), p)
    }

    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sdr: ActorRef): Future[Any] = {
      replay(
        Key(initiatingChannelId = cmd.channelId, sequenceNr = cmd.fromSequenceNr),
        Key(initiatingChannelId = cmd.channelId, sequenceNr = cmd.toSequenceNr), p)
    }

    def replay(startKey: Key, stopKey: Key, p: (Message) => Unit): Future[Any] = {

      val chunkSize = props.replayChunkSize
      val promise = Promise[Any]()

      def fetch(startKey: Key, stopKey: Key) = {
        val qry = BSONDocument(
          "$query" -> BSONDocument(
            "processorId"         -> BSONInteger(startKey.processorId),
            "initiatingChannelId" -> BSONInteger(startKey.initiatingChannelId),
            "sequenceNr"          -> BSONDocument("$gte" -> BSONLong(startKey.sequenceNr), "$lte"  -> BSONLong(stopKey.sequenceNr))),
          "$orderby" -> BSONDocument("sequenceNr" -> BSONLong(1)))
        collection.find(qry).cursor[ReactiveMessage].toList()
      }

      def result(startKey: Key, stopKey: Key): Future[List[Message]] = {
        val promise = Promise[List[Message]]()
        fetch(startKey, stopKey) onComplete {
          case Success(s) => {
            val msgs = s.filter(rm => rm.key.confirmingChannelId == 0)
            val acks = s.filter(rm => rm.key.confirmingChannelId > 0)
            promise.success {
              for {
                rm <- msgs
                msg = msgFromBytes(rm.msgAsBytes).copy(acks = acks.filter(ack => ack.key.sequenceNr == rm.key.sequenceNr).map(_.key.confirmingChannelId))
              } yield msg
            }
          }
          case Failure(e) => promise.failure(e)
        }
        promise.future
      }

      def go(startKey: Key, stopKey: Key) {
        val from = startKey.sequenceNr
        val to = stopKey.sequenceNr
        if (from > to) promise.success(())
        else if ((to - from) < chunkSize) result(startKey, stopKey) onComplete {
          case Success(s) => { s.foreach(p); promise.success(()) }
          case Failure(e) => promise.failure(e)
        } else result(startKey, startKey.withSequenceNr(_ + chunkSize)) onComplete {
            case Success(s) => { s.foreach(p); go(startKey.withSequenceNr(_ + chunkSize + 1), stopKey) }
            case Failure(e) => promise.failure(e)
        }
      }

      go(startKey, stopKey)
      promise.future
    }
  }

  def storedCounter = {
    def storedCounter: Future[Long] = {
      collection
        .find(BSONDocument())
        .sort(BSONDocument("sequenceNr" -> -1)).cursor[ReactiveMessage].headOption().map {
        case Some(doc) => doc.key.sequenceNr
        case None => 0L
      }
    }
    Await.result(storedCounter, props.initTimeout)
  }

  override def start() {
    initSnapshotting()

    // user the applications actor system.
    driver = new MongoDriver(context.system)

    connection = driver.connection(props.nodes, props.authentications, props.nbChannelsPerNode, props.mongoDBSystemName)
    val db = DB(props.dbName, connection)
    collection = db[BSONCollection](props.collName)

     // Create unique index as ObjectId is used as "_id".
    val idx1: Index = Index(List(
      "processorId"         -> IndexType.Ascending,
      "initiatingChannelId" -> IndexType.Ascending,
      "sequenceNr"          -> IndexType.Ascending,
      "confirmingChannelId" -> IndexType.Ascending), unique = true)

    // Create index for replay.fetch
    val idx2: Index = Index(List(
      "processorId"         -> IndexType.Ascending,
      "initiatingChannelId" -> IndexType.Ascending,
      "sequenceNr"          -> IndexType.Ascending))

    // Create index for storedCounter
    val idx3: Index = Index(List("sequenceNr" -> IndexType.Ascending))

    // Enforce the index.
    val idxs: IndexesManager = new IndexesManager(db)
    Await.result(idxs.ensure(NSIndex(collection.fullCollectionName, idx1)), props.initTimeout)
    Await.result(idxs.ensure(NSIndex(collection.fullCollectionName, idx2)), props.initTimeout)
    Await.result(idxs.ensure(NSIndex(collection.fullCollectionName, idx3)), props.initTimeout)
  }

  override def stop() {
    import scala.concurrent.duration._
    implicit val timeout = 30.seconds
    driver.close()
  }
}

case class ReactiveMessage(id: BSONObjectID = BSONObjectID.generate, key: Key, msgAsBytes: Array[Byte] = Array.empty[Byte])

object ReactiveMessage {

  implicit object ReactiveMessageReader extends BSONDocumentReader[ReactiveMessage] {
    def read(doc: BSONDocument): ReactiveMessage = {
      ReactiveMessage(doc.getAs[BSONObjectID]("_id").get,
        Key(doc.getAs[Int]("processorId").get,
          doc.getAs[Int]("initiatingChannelId").get,
          doc.getAs[Long]("sequenceNr").get,
          doc.getAs[Int]("confirmingChannelId").get),
        doc.getAs[BSONBinary]("message").map(data => data.value.readArray(data.value.size)).get)
    }
  }

  implicit object ReactiveMessageWriter extends BSONDocumentWriter[ReactiveMessage] {
    def write(reactiveMsg: ReactiveMessage) = BSONDocument(
      "_id"                 -> reactiveMsg.id,
      "processorId"         -> BSONInteger(reactiveMsg.key.processorId),
      "initiatingChannelId" -> BSONInteger(reactiveMsg.key.initiatingChannelId),
      "sequenceNr"          -> BSONLong(reactiveMsg.key.sequenceNr),
      "confirmingChannelId" -> BSONInteger(reactiveMsg.key.confirmingChannelId),
      "message"             -> BSONBinary(reactiveMsg.msgAsBytes, Subtype.GenericBinarySubtype))
  }
}
