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
package org.eligosource.eventsourced.journal

import scala.concurrent._

import java.nio.ByteBuffer

import com.stumbleupon.async.{Callback, Deferred}

import org.apache.hadoop.hbase.util.Bytes

package object hbase {
  val TableName = "event"
  val TableNameBytes = Bytes.toBytes(TableName)

  val ColumnFamilyName = "ef"
  val ColumnFamilyNameBytes = Bytes.toBytes(ColumnFamilyName)

  val MsgColumnName = "msg"
  val MsgColumnNameBytes = Bytes.toBytes(MsgColumnName)

  val TimestampColumnName = "tms"
  val TimestampColumnNameBytes = Bytes.toBytes(TimestampColumnName)

  val AckColumnPrefix = "ack"
  def ackColumnBytes(channelId: Int) = Bytes.toBytes(AckColumnPrefix + channelId)

  private [hbase] case class InMsgKey(partition: Int, processorId: Int, sequenceNumber: Long)
  private [hbase] case class OutMsgKey(partition: Int, channelId: Int, sequenceNumber: Long)

  private [hbase] trait KeyRepresentation[T] {
    def partition(key: T): Int
    def source(key: T): Int
    def sequenceNr(key: T): Long
    def create(partition: Int, source: Int, sequenceNr: Long): T
  }

  implicit def keyFromBytes[T : KeyRepresentation](bytes: Array[Byte]): T = {
    val bb = ByteBuffer.wrap(bytes)
    val partition = bb.getInt
    val processorId = bb.getInt
    val sequenceNr = bb.getLong
    implicitly[KeyRepresentation[T]].create(partition, processorId, sequenceNr)
  }

  implicit def keyToBytes[T : KeyRepresentation](key: T): Array[Byte] = {
    val bb = ByteBuffer.allocate(16)
    val kr = implicitly[KeyRepresentation[T]]
    bb.putInt(kr.partition(key))
    bb.putInt(kr.source(key))
    bb.putLong(kr.sequenceNr(key))
    bb.array
  }

  private [hbase] implicit object InMsgKeyRepresentation extends KeyRepresentation[InMsgKey] {
    def partition(key: InMsgKey) = key.partition
    def source(key: InMsgKey) = key.processorId
    def sequenceNr(key: InMsgKey) = key.sequenceNumber
    def create(partition: Int, source: Int, sequenceNr: Long) =
      InMsgKey(partition, source, sequenceNr)
  }

  private [hbase] implicit object OutMsgKeyRepresentation extends KeyRepresentation[OutMsgKey] {
    def partition(key: OutMsgKey) = key.partition
    def source(key: OutMsgKey) = - key.channelId
    def sequenceNr(key: OutMsgKey) = key.sequenceNumber
    def create(partition: Int, source: Int, sequenceNr: Long) =
      OutMsgKey(partition, -source, sequenceNr)
  }

  implicit def deferredToFuture[A](d: Deferred[A]): Future[A] = {
    val promise = Promise[A]()
    d.addCallback(new Callback[Unit, A] {
      def call(a: A) = promise.success(a)
    })
    d.addErrback(new Callback[Unit, Throwable] {
      def call(t: Throwable) = promise.failure(t)
    })
    promise.future
  }

  // -------------------------
  //  temporary ...
  // -------------------------

  private [hbase] def bitString[T : KeyRepresentation](key: T): String = {
    val r = implicitly[KeyRepresentation[T]]
    "%s-%s-%s" format(
      bitString(r.partition(key)),
      bitString(r.source(key)),
      bitString(r.sequenceNr(key)))
  }

  private [hbase] def bitString(i: Int): String = (for {
    p <- 31 to 0 by -1
  } yield (i >> p) & 1) mkString("")

  private [hbase] def bitString(l: Long): String = (for {
    p <- 64 to 0 by -1
  } yield (l >> p) & 1) mkString("")
}
