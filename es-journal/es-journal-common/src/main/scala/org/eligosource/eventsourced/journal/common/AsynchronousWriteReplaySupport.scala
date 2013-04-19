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
package org.eligosource.eventsourced.journal.common

import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import org.eligosource.eventsourced.core._

trait AsynchronousWriteReplaySupport extends Actor {
  import AsynchronousWriteReplaySupport._
  import Channel.Deliver
  import Journal._

  val deadLetters = context.system.deadLetters
  val resequencer: ActorRef =
    actor(new ResequencerActor(replayer), dispatcherName = journalProps.dispatcherName)

  val writers = 0 until asyncWriterCount map { id =>
    actor(new WriterActor(writer(id)), dispatcherName = journalProps.dispatcherName)
  } toVector

  var _counter = 0L
  var _counterResequencer = 1L

  def journalProps: JournalProps

  val asyncWriteTimeoutObj = Timeout(asyncWriteTimeout)
  def asyncWriteTimeout: FiniteDuration
  def asyncWriterCount: Int

  def counter = _counter
  def counterResequencer = _counterResequencer

  def writer(id: Int): Writer
  def replayer: Replayer

  import context.dispatcher

  def asyncWriteAndResequence(cmd: Any) {
    val ctr = counterResequencer
    val sdr = sender
    val idx = counter % asyncWriterCount
    val write = writers(idx.toInt).ask(cmd)(asyncWriteTimeoutObj)
    write onSuccess { case _ => resequencer tell ((ctr, cmd), sdr) }
    write onFailure { case t => resequencer tell ((ctr, WriteFailed(cmd, t)), sdr) }
    _counterResequencer += 1L
  }

  def asyncResequence(cmd: Any, inc: Long = 1L) {
    resequencer forward (counterResequencer, cmd)
    _counterResequencer += inc
  }

  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      asyncWriteAndResequence(c.withTimestamp)
      _counter += 1L
    }
    case cmd: WriteOutMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      asyncWriteAndResequence(c)
      _counter += 1L
    }
    case cmd: WriteAck => {
      asyncWriteAndResequence(cmd)
    }
    case cmd: DeleteOutMsg => {
      asyncWriteAndResequence(cmd)
    }
    case cmd: Loop => {
      asyncResequence(cmd)
    }
    case cmd: BatchReplayInMsgs => {
      asyncResequence(IsolatedReplay(cmd, counter - 1L), 2L)
    }
    case cmd: ReplayInMsgs => {
      asyncResequence(IsolatedReplay(cmd, counter - 1L), 2L)
    }
    case cmd: ReplayOutMsgs => {
      asyncResequence(IsolatedReplay(cmd, counter - 1L), 2L)
    }
    case cmd: BatchDeliverOutMsgs => {
      asyncResequence(cmd)
    }
    case cmd: SetCommandListener => {
      resequencer ! cmd
    }
  }

  override def preStart() {
    start()
    _counter = storedCounter + 1L
  }

  override def postStop() {
    stop()
  }

  def start() {}
  def stop() {}

  def storedCounter: Long

  trait Writer {
    def executeWriteInMsg(cmd: WriteInMsg): Future[Any]
    def executeWriteOutMsg(cmd: WriteOutMsg): Future[Any]
    def executeWriteAck(cmd: WriteAck): Future[Any]
    def executeDeleteOutMsg(cmd: DeleteOutMsg): Future[Any]
  }

  trait Replayer {
    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sdr: ActorRef, toSequenceNr: Long): Future[Any]
    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sdr: ActorRef, toSequenceNr: Long): Future[Any]
    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sdr: ActorRef, toSequenceNr: Long): Future[Any]
  }

  class WriterActor(writer: Writer) extends Actor {
    def receive = {
      case cmd: WriteInMsg   => writer.executeWriteInMsg(cmd) pipeTo sender
      case cmd: WriteOutMsg  => writer.executeWriteOutMsg(cmd) pipeTo sender
      case cmd: WriteAck     => writer.executeWriteAck(cmd) pipeTo sender
      case cmd: DeleteOutMsg => writer.executeDeleteOutMsg(cmd) pipeTo sender
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
      sender ! Status.Failure(reason)
    }
  }

  class ResequencerActor(replayer: Replayer) extends Actor {
    import scala.collection.mutable.Map

    private val delayed = Map.empty[Long, (Any, ActorRef)]
    private var delivered = 0L
    private var commandListener: Option[ActorRef] = None

    def receive = {
      case (seqnr: Long, cmd) => resequence(seqnr, cmd, sender)
      case SetCommandListener(cl) => commandListener = cl
    }

    def execute(cmd: Any, sdr: ActorRef, seqnr: Long) = cmd match {
      case c: WriteInMsg => {
        c.target tell (Written(c.message), sdr)
        commandListener.foreach(_ ! cmd)
      }
      case c: WriteOutMsg => {
        c.target tell (Written(c.message), sdr)
        commandListener.foreach(_ ! cmd)
      }
      case c: WriteAck => {
        commandListener.foreach(_ ! cmd)
      }
      case c: DeleteOutMsg => {
        commandListener.foreach(_ ! cmd)
      }
      case Loop(msg, target) => {
        target tell (Looped(msg), sdr)
      }
      case IsolatedReplay(cmd @ BatchReplayInMsgs(replays), toSequenceNr) => {
        replayer.executeBatchReplayInMsgs(replays, (msg, target) => target tell (Written(msg), deadLetters), sdr, toSequenceNr) onComplete {
          case Success(_) => { self ! (seqnr + 1L, ReplayDone); sdr ! ReplayDone }
          case Failure(e) => { self ! (seqnr + 1L, ReplayFailed(cmd, e)) }
        }
      }
      case IsolatedReplay(cmd: ReplayInMsgs, toSequenceNr) => {
        replayer.executeReplayInMsgs(cmd, msg => cmd.target tell (Written(msg), deadLetters), sdr, toSequenceNr) onComplete {
          case Success(_) => { self ! (seqnr + 1L, ReplayDone); sdr ! ReplayDone }
          case Failure(e) => { self ! (seqnr + 1L, ReplayFailed(cmd, e)) }
        }
      }
      case IsolatedReplay(cmd: ReplayOutMsgs, toSequenceNr) => {
        replayer.executeReplayOutMsgs(cmd, msg => cmd.target tell (Written(msg), deadLetters), sdr, toSequenceNr) onComplete {
          case Success(_) => self ! (seqnr + 1L, ReplayDone)
          case Failure(e) => self ! (seqnr + 1L, ReplayFailed(cmd, e))
        }
      }
      case BatchDeliverOutMsgs(channels) => {
        channels.foreach(_ ! Deliver)
        sdr ! DeliveryDone
      }
      case ReplayDone => {
        // nothing to do ...
      }
      case e: ReplayFailed => {
        context.system.eventStream.publish(e)
      }
      case e: WriteFailed => {
        context.system.eventStream.publish(e)
      }
    }

    @scala.annotation.tailrec
    private def resequence(seqnr: Long, cmd: Any, sdr: ActorRef) {
      if (seqnr == delivered + 1) {
        delivered = seqnr
        execute(cmd, sdr, seqnr)
      } else {
        delayed += (seqnr -> (cmd, sender))
      }
      val eo = delayed.remove(delivered + 1)
      if (eo.isDefined) resequence(delivered + 1, eo.get._1, eo.get._2)
    }
  }
}

object AsynchronousWriteReplaySupport {
  case class WriteFailed(cmd: Any, cause: Throwable)
  case class ReplayFailed(replayCmd: Any, cause: Throwable)
  case class IsolatedReplay(replayCmd: Any, toSequencerNr: Long)
}
