/*
 * Copyright 2012 Eligotech BV.
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
package org.eligosource.eventsourced.core

import scala.collection.immutable.Queue

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util._

trait Channel extends Actor {
  def id: Int

  implicit val executionContext = context.dispatcher

  var destination: Option[ActorRef] = None
  var replyDestination: Option[ActorRef] = None
}

object Channel {
  val JournalTimeout = Timeout(10 seconds)
  val DestinationTimeout = Timeout(5 seconds)
  val ReplyDestinationTimeout = Timeout(5 seconds)

  case class SetDestination(destination: ActorRef)
  case class SetReplyDestination(replayDestination: ActorRef)

  case object Deliver
}

/**
 * A channel that sends event messages to a destination. If the destination responds,
 * an acknowledgement is written to the journal. If the destination does not respond
 * or responds with Status.Failure, no acknowledgement is written.
 */
class DefaultChannel(val id: Int, val journal: ActorRef) extends Channel {
  import Channel._

  assert(id > 0)

  var retain = true
  var buffer = List.empty[Message]

  def receive = {
    case msg: Message if (!msg.acks.contains(id)) => {
      if (retain) buffer = msg :: buffer
      else send(msg)
    }
    case Deliver => {
      retain = false
      buffer.reverse.foreach(send)
      buffer = Nil
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
    case SetReplyDestination(rd) => {
      replyDestination = Some(rd)
    }
  }

  def send(msg: Message): Unit = destination foreach { d =>
    val r = for {
      r1 <- d.ask(msg)(DestinationTimeout)
      r2 <- reply(r1)
    } yield r2

    r onSuccess {
      case _ if (msg.ack) => journal.!(WriteAck(msg.processorId, id, msg.sequenceNr))(null)
    }
  }

  def reply(msg: Any): Future[Any] =
    replyDestination.map(_.ask(msg)(ReplyDestinationTimeout)).getOrElse(Promise.successful(Ack))
}

case class ReliableChannelConf(
  recoveryDelay: Duration,
  retryDelay: Duration,
  retryMax: Int
)

object ReliableChannelConf {
  val DefaultRecoveryDelay = 5 seconds
  val DefaultRetryDelay = 1 second
  val DefaultRetryMax = 3

  def apply() = new ReliableChannelConf(DefaultRecoveryDelay, DefaultRetryDelay, DefaultRetryMax)
}

/**
 * A channel that stores output messages in the journal before sending them to a destination.
 * If the destination responds, the stored output message is removed from the journal. If
 * the destination does not respond or responds with Status.Failure, a re-delivery is attempted.
 */
class ReliableChannel(val id: Int, journal: ActorRef, conf: ReliableChannelConf) extends Channel {
  import Channel._

  assert(id > 0)

  var buffer: Option[ActorRef] = None

  def receive = {
    case msg: Message if (!msg.acks.contains(id)) => {
      val ackSequenceNr: Long = if (msg.ack) msg.sequenceNr else SkipAck
      journal.!(WriteOutMsg(id, msg, msg.processorId, ackSequenceNr, buffer.getOrElse(context.system.deadLetters)))(null)
    }
    case Deliver => destination foreach { d =>
      buffer = Some(createBuffer(d))
      deliverPendingMessages(buffer.get)
    }
    case Terminated(s) => buffer foreach { b =>
      buffer = None
      context.system.scheduler.scheduleOnce(conf.recoveryDelay, self, Deliver)
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
    case SetReplyDestination(rd) => {
      replyDestination = Some(rd)
    }
  }

  def deliverPendingMessages(destination: ActorRef) {
    journal.!(ReplayOutMsgs(id, 0L, destination))(null)
  }

  def createBuffer(destination: ActorRef) = {
    context.watch(context.actorOf(Props(new ReliableChannelBuffer(id, journal, destination, replyDestination, conf))))
  }
}

private [core] object ReliableChannel {
  case class Next(retries: Int)
  case class Retry(msg: Message)

  case object Trigger
  case object FeedMe
}

private [core] class ReliableChannelBuffer(channelId: Int, journal: ActorRef, destination: ActorRef, replyDestination: Option[ActorRef], conf: ReliableChannelConf) extends Actor {
  import ReliableChannel._

  var rocSenderQueue = Queue.empty[Message]
  var rocSenderBusy = false //

  val rocSender = context.actorOf(Props(new ReliableChannelSender(channelId, journal, destination, replyDestination, conf)))

  def receive = {
    case msg: Message => {
      rocSenderQueue = rocSenderQueue.enqueue(msg)
      if (!rocSenderBusy) {
        rocSenderBusy = true
        rocSender ! Trigger
      }
    }
    case FeedMe => {
      if (rocSenderQueue.size == 0) {
        rocSenderBusy = false
      } else {
        rocSender ! rocSenderQueue
        rocSenderQueue = Queue.empty
      }
    }
  }
}

private [core] class ReliableChannelSender(channelId: Int, journal: ActorRef, destination: ActorRef, replyDestination: Option[ActorRef], conf: ReliableChannelConf) extends Actor {
  import Channel._
  import ReliableChannel._

  implicit val executionContext = context.dispatcher

  var sequencer: Option[ActorRef] = None
  var queue = Queue.empty[Message]

  var retries = 0

  def receive = {
    case Trigger => {
      sender ! FeedMe
    }
    case q: Queue[Message] => {
      sequencer = Some(sender)
      queue = q
      self ! Next(retries)
    }
    case Next(r) => if (queue.size > 0) {
      val (msg, q) = queue.dequeue

      retries = r
      queue = q

      val future = send(msg)
      val ctx = context


      future onSuccess {
        case _ => {
          journal.!(DeleteOutMsg(channelId, msg.sequenceNr))(null)
          self ! Next(0)
        }
      }

      future onFailure {
        case e => ctx.system.scheduler.scheduleOnce(conf.retryDelay, self, Retry(msg))
      }
    } else {
      sequencer.foreach(_ ! FeedMe)
    }
    case Retry(msg) => {
      // undo dequeue
      queue = (msg +: queue)
      // and try again ...
      if (retries < conf.retryMax) self ! Next(retries + 1) else sequencer.foreach(s => context.stop(s))
    }
  }

  def send(msg: Message): Future[Any] = for {
    r1 <- destination.ask(msg)(DestinationTimeout)
    r2 <- reply(r1)
  } yield r2

  def reply(msg: Any): Future[Any] =
    replyDestination.map(_.ask(msg)(ReplyDestinationTimeout)).getOrElse(Promise.successful(Ack))
}
