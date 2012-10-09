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

/**
 * A channel is used by [[org.eligosource.eventsourced.core.Eventsourced]]
 * processors to send event [[org.eligosource.eventsourced.core.Message]]s
 * to destinations. A destination can be any actor that acknowledges the
 * receipt of an event message by replying with an `Ack` (or a response
 * [[org.eligosource.eventsourced.core.Message]] when there's a `replyDestination`
 * set for the channel).
 *
 * During recovery, a channel prevents that replayed messages are redundantly
 * delivered to destinations but only if that channel has previously written
 * an acknowledgement for that message to the journal.
 *
 * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
 *      [[org.eligosource.eventsourced.core.ReliableChannel]]
 *      [[org.eligosource.eventsourced.core.Receiver]]
 *      [[org.eligosource.eventsourced.core.Responder]]
 *      [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 */
trait Channel extends Actor {
  implicit val executionContext = context.dispatcher

  /**
   * Channel id.
   */
  def id: Int

  /**
   * Channel destination. Set when a channel is created via
   * `EventsourcingExtension.channelOf`.
   */
  var destination: Option[ActorRef] = None

  /**
   * Channel reply destination. Optionally set when a channel is created via
   * `EventsourcingExtension.channelOf`. If set, responses from destinations
   * are routed to the reply destination. The order of messages sent to the
   * reply destination does not necessarily correlate with the order of
   * messages sent to the destination.
   */
  var replyDestination: Option[ActorRef] = None
}

private [core] object Channel {
  val JournalTimeout = Timeout(10 seconds)
  val DestinationTimeout = Timeout(5 seconds)
  val ReplyDestinationTimeout = Timeout(5 seconds)

  case class SetDestination(destination: ActorRef)
  case class SetReplyDestination(replayDestination: ActorRef)
}

/**
 * A transient channel that sends event [[org.eligosource.eventsourced.core.Message]]s
 * to destinations. If the `destination` responds, an acknowledgement is written by the
 * channel to the journal. If a `replyDestination` is set, the  acknowledgement will be
 * written when the reply destination responds. If the `destination` or the `replyDestination`
 * do not respond (i.e. when a timeout occurs) or respond with a `Status.Failure` then
 * no acknowledgement is written.
 *
 * @param id channel id.
 * @param journal journal of the context that created this channel.
 *
 * @see [[org.eligosource.eventsourced.core.Channel]]
 */
class DefaultChannel(val id: Int, val journal: ActorRef) extends Channel {
  import Channel._

  require(id > 0, "channel id must be a positive integer")

  private val successfulAck = Promise.successful(Ack)
  private var retain = true
  private var buffer = List.empty[Message]

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

  private def send(msg: Message): Unit = destination foreach { d =>
    val r = for {
      r1 <- d.ask(msg)(DestinationTimeout)
      r2 <- reply(r1)
    } yield r2

    r onSuccess {
      case _ if (msg.ack) => journal.!(WriteAck(msg.processorId, id, msg.sequenceNr))(null)
    }
  }

  private def reply(msg: Any): Future[Any] =
    replyDestination.map(_.ask(msg)(ReplyDestinationTimeout)).getOrElse(successfulAck)
}

/**
 * Redelivery policy for a [[org.eligosource.eventsourced.core.ReliableChannel]].
 *
 * @param recoveryDelay Delay for re-starting a reliable channel that has been stopped
 *        after having reached the maximum number of re-delivery attempts.
 * @param retryDelay Delay between re-delivery attempts.
 * @param retryMax Maximum number of re-delivery attempts.
 */
case class RedeliveryPolicy(
  recoveryDelay: Duration,
  retryDelay: Duration,
  retryMax: Int)

object RedeliveryPolicy {
  /** Default recovery delay: 5 seconds */
  val DefaultRecoveryDelay = 5 seconds
  /** Default re-delivery delay: 1 second */
  val DefaultRetryDelay = 1 second
  //** Default maximum number of re-deliveries: 3 */
  val DefaultRetryMax = 3

  /**
   * Returns a [[org.eligosource.eventsourced.core.RedeliveryPolicy]] with default settings.
   */
  def apply() = new RedeliveryPolicy(DefaultRecoveryDelay, DefaultRetryDelay, DefaultRetryMax)
}

/**
 * A persistent channel that sends event [[org.eligosource.eventsourced.core.Message]]s to
 * destinations. Every event message sent to this channel is stored in the journal together
 * with an acknowledgement. If the `destination` responds, the stored message will be deleted
 * from the journal (but not the acknowledgement). If a `replyDestination` is set, the stored
 * message will be deleted when the reply destination responds. If the `destination` or
 * `replyDestination` does not respond (i.e. when a timeout occurs) or respond with a
 * `Status.Failure`, a re-delivery is attempted. If the maximum number of re-delivery attempts
 * have been made, the channel restarts itself after a certain ''recovery delay'' (and starts
 * again with re-deliveries).
 *
 * @param id channel id.
 * @param journal journal of the context that created this channel.
 * @param policy redelivery policy for the channel.
 *
 * @see [[org.eligosource.eventsourced.core.Channel]]
 *      [[org.eligosource.eventsourced.core.RedeliveryPolicy]]
 */
class ReliableChannel(val id: Int, journal: ActorRef, policy: RedeliveryPolicy) extends Channel {
  import Channel._

  require(id > 0, "channel id must be a positive integer")

  private var buffer: Option[ActorRef] = None

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
      context.system.scheduler.scheduleOnce(policy.recoveryDelay, self, Deliver)
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
    case SetReplyDestination(rd) => {
      replyDestination = Some(rd)
    }
  }

  private def deliverPendingMessages(destination: ActorRef) {
    journal.!(ReplayOutMsgs(id, 0L, destination))(null)
  }

  private def createBuffer(destination: ActorRef) = {
    context.watch(context.actorOf(Props(new ReliableChannelBuffer(id, journal, destination, replyDestination, policy))))
  }
}

private [core] object ReliableChannel {
  case class Next(retries: Int)
  case class Retry(msg: Message)

  case object Trigger
  case object FeedMe
}

private [core] class ReliableChannelBuffer(channelId: Int, journal: ActorRef, destination: ActorRef, replyDestination: Option[ActorRef], policy: RedeliveryPolicy) extends Actor {
  import ReliableChannel._

  var rocSenderQueue = Queue.empty[Message]
  var rocSenderBusy = false //

  val rocSender = context.actorOf(Props(new ReliableChannelSender(channelId, journal, destination, replyDestination, policy)))

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

private [core] class ReliableChannelSender(channelId: Int, journal: ActorRef, destination: ActorRef, replyDestination: Option[ActorRef], policy: RedeliveryPolicy) extends Actor {
  import Channel._
  import ReliableChannel._

  implicit val executionContext = context.dispatcher

  val successfulAck = Promise.successful(Ack)
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
        case e => ctx.system.scheduler.scheduleOnce(policy.retryDelay, self, Retry(msg))
      }
    } else {
      sequencer.foreach(_ ! FeedMe)
    }
    case Retry(msg) => {
      // undo dequeue
      queue = (msg +: queue)
      // and try again ...
      if (retries < policy.retryMax) self ! Next(retries + 1) else sequencer.foreach(s => context.stop(s))
    }
  }

  def send(msg: Message): Future[Any] = for {
    r1 <- destination.ask(msg)(DestinationTimeout)
    r2 <- reply(r1)
  } yield r2

  def reply(msg: Any): Future[Any] =
    replyDestination.map(_.ask(msg)(ReplyDestinationTimeout)).getOrElse(successfulAck)
}
