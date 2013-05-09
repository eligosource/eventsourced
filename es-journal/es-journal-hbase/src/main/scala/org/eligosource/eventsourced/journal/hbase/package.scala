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
  val DefaultTableName = "event"
  val DefaultPartitionCount = 4

  val ColumnFamilyName = "ef"
  val ColumnFamilyNameBytes = Bytes.toBytes(ColumnFamilyName)

  val MsgColumnName = "msg"
  val MsgColumnNameBytes = Bytes.toBytes(MsgColumnName)

  val SequenceNrColumnName = "snr"
  val SequenceNrColumnNameBytes = Bytes.toBytes(SequenceNrColumnName)

  val PartitionCountColumnName = "pct"
  val PartitionCountColumnNameBytes = Bytes.toBytes(PartitionCountColumnName)

  val TimestampColumnName = "tms"
  val TimestampColumnNameBytes = Bytes.toBytes(TimestampColumnName)

  val AckColumnPrefix = "ack"
  def ackColumnBytes(channelId: Int) = Bytes.toBytes(AckColumnPrefix + channelId)

  class PartitionCountNotFoundException(table: String) extends Exception(
    s"Partition count not stored in table ${table}"
  )

  private [hbase] sealed trait Key {
    def partition: Int
    def source: Int
    def sequenceNumber: Long
    def withSequenceNumber(f: Long => Long): Key
    def withPartition(p: Int): Key
    def toBytes = {
      val bb = ByteBuffer.allocate(16)
      bb.putInt(partition)
      bb.putInt(source)
      bb.putLong(sequenceNumber)
      bb.array
    }
  }

  private [hbase] case class InMsgKey(partition: Int, processorId: Int, sequenceNumber: Long) extends Key {
    def source = processorId
    def withSequenceNumber(f: (Long) => Long) = copy(sequenceNumber = f(sequenceNumber))
    def withPartition(p: Int) = copy(partition = p)
  }

  private [hbase] case class OutMsgKey(partition: Int, channelId: Int, sequenceNumber: Long) extends Key {
    def source = -channelId
    def withSequenceNumber(f: (Long) => Long) = copy(sequenceNumber = f(sequenceNumber))
    def withPartition(p: Int) = copy(partition = p)
  }

  private [hbase] case class CounterKey(partition: Int, sequenceNumber: Long) extends Key {
    val source = 0
    def withSequenceNumber(f: (Long) => Long) =  copy(sequenceNumber = f(sequenceNumber))
    def withPartition(p: Int) = copy(partition = p)
  }

  private [hbase] case class PartitionCountKey(upper: Boolean = false) extends Key {
    val partition = -1
    val source = 0
    val sequenceNumber = if (upper) 1L else 0L

    def withPartition(p: Int) =
      throw new UnsupportedOperationException("withPartition on PartitionCountKey")

    def withSequenceNumber(f: (Long) => Long) =
      throw new UnsupportedOperationException("withSequenceNumber on PartitionCountKey")
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

  private [hbase] def longToBytes(l: Long): Array[Byte] =
    ByteBuffer.allocate(8).putLong(l).array()

  private [hbase] def longFromBytes(b: Array[Byte]): Long =
    ByteBuffer.wrap(b).getLong

  private [hbase] def bitString(key: Key): String = {
    "%s-%s-%s" format(
      bitString(key.partition),
      bitString(key.source),
      bitString(key.sequenceNumber))
  }

  private [hbase] def bitString(i: Int): String = (for {
    p <- 31 to 0 by -1
  } yield (i >> p) & 1) mkString("")

  private [hbase] def bitString(l: Long): String = (for {
    p <- 64 to 0 by -1
  } yield (l >> p) & 1) mkString("")
}
