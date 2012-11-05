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

import scala.concurrent.duration._
import scala.collection.immutable.Queue

import akka.actor._

/**
 * A channel keeps track of successfully delivered event [[org.eligosource.eventsourced.core.Message]]s.
 * Channels are used by [[org.eligosource.eventsourced.core.Eventsourced]] actors to prevent redundant
 * message delivery to destinations during event message replay.
 *
 * Channels need not be used by `Eventsourced` actors if the event message destination was received via
 * a sender reference. Sender references are always the `deadLetters` reference during a replay.
 *
 * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
 *      [[org.eligosource.eventsourced.core.ReliableChannel]]
 *      [[org.eligosource.eventsourced.core.Message]]
 *      [[org.eligosource.eventsourced.core.Confirm]]
 *      [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 */
trait Channel extends Actor {
  private val extension = EventsourcingExtension(context.system)
  implicit val executionContext = context.dispatcher

  /**
   * Channel id. Must be a positive integer.
   */
  def id: Int

  /**
   * Channel destination.
   */
  def destination: ActorRef

  /**
   * De-registers this channel from
   * [[org.eligosource.eventsourced.core.EventsourcingExtension]].
   */
  override def postStop() {
    extension.deregisterChannel(id)
  }
}

private [core] object Channel {
  val DeliveryTimeout = 5 seconds
}

/**
 * A transient channel that sends event [[org.eligosource.eventsourced.core.Message]]s
 * to `destination`. If `destination` positively confirms the receipt of an event message
 * with `Message.confirm()` an ''acknowledgement'' is written to the journal. In all
 * other cases no action is taken. Acknowledgements are used during replay to decide
 * if a channel should ignore a message or not.
 *
 * A `DefaultChannel` preserves the `sender` reference (i.e. forwards it to `destination`).
 *
 * @param id channel id. Must be a positive integer.
 * @param journal journal of the [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 *        at which this channel is registered.
 * @param destination delivery destination of event messages added to this channel.
 *
 * @see [[org.eligosource.eventsourced.core.Channel]]
 */
class DefaultChannel(val id: Int, val journal: ActorRef, val destination: ActorRef) extends Channel {
  require(id > 0, "channel id must be a positive integer")

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
  }

  def send(msg: Message) {
    val pct = if (msg.ack) journal else null
    val pcm = if (msg.ack) WriteAck(msg.processorId, id, msg.sequenceNr) else null
    destination forward msg.copy(posConfirmationTarget = pct, posConfirmationMessage = pcm)
  }
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
  recoveryDelay: FiniteDuration,
  retryDelay: FiniteDuration,
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
 * `destination`. Every event message sent to this channel is stored in the journal together
 * with an ''acknowledgement'' (which is used during replay to decide if the channel should
 * ignore a message or not). If `destination` positively confirms the receipt of an event
 * message with `Message.confirm()` the stored message is deleted from the journal. If
 * `destination` negatively confirms the receipt of an event message with `Message.confirm(false)`
 * or no confirmation was made (i.e. a timeout occurred), a re-delivery attempt is made. If the
 * maximum number of re-delivery attempts have been made, the channel restarts itself after
 * a certain ''recovery delay'' (and starts again with re-deliveries).
 *
 * A `ReliableChannel` preserves the `sender` reference (i.e. forwards it to `destination`) only
 * after initial delivery (which occurs, for example, during `EventsourcingExtension.recover()`
 * or `EventsourcingExtension.deliver()`).
 *
 * @param id channel id. Must be a positive integer.
 * @param journal journal of the [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 *        at which this channel is registered.
 * @param destination delivery destination of event messages added to this channel.
 *
 * @see [[org.eligosource.eventsourced.core.Channel]]
 *      [[org.eligosource.eventsourced.core.RedeliveryPolicy]]
 */
class ReliableChannel(val id: Int, val journal: ActorRef, val destination: ActorRef, policy: RedeliveryPolicy) extends Channel {
  require(id > 0, "channel id must be a positive integer")

  private var buffer: Option[ActorRef] = None

  def receive = {
    case msg: Message if (!msg.acks.contains(id)) => {
      val ackSequenceNr: Long = if (msg.ack) msg.sequenceNr else SkipAck
      journal forward WriteOutMsg(id, msg, msg.processorId, ackSequenceNr, buffer.getOrElse(context.system.deadLetters))
    }
    case Deliver => {
      if (!buffer.isDefined) buffer = Some(createBuffer(destination))
      deliverPendingMessages(buffer.get)
    }
    case Terminated(s) => buffer foreach { b =>
      buffer = None
      context.system.scheduler.scheduleOnce(policy.recoveryDelay, self, Deliver)
    }
  }

  private def deliverPendingMessages(dst: ActorRef) {
    journal ! ReplayOutMsgs(id, 0L, dst)
  }

  private def createBuffer(dst: ActorRef) = {
    context.watch(context.actorOf(Props(new ReliableChannelBuffer(id, journal, dst, policy))))
  }
}

private [core] object ReliableChannel {
  case class Next(retries: Int)
  case class Retry(msg: Message, sdr: ActorRef)

  case object Trigger
  case object FeedMe

  case class Confirmed(snr: Long, pos: Boolean = true)
  case class ConfirmationTimeout(snr: Long)
}

private [core] class ReliableChannelBuffer(channelId: Int, journal: ActorRef, destination: ActorRef, policy: RedeliveryPolicy) extends Actor {
  import ReliableChannel._

  var delivererQueue = Queue.empty[(Message, ActorRef)]
  var delivererBusy = false

  val deliverer = context.actorOf(Props(new ReliableChannelDeliverer(channelId, journal, destination, policy)))

  def receive = {
    case Written(msg) => {
      delivererQueue = delivererQueue.enqueue(msg, sender)
      if (!delivererBusy) {
        delivererBusy = true
        deliverer ! Trigger
      }
    }
    case FeedMe => {
      if (delivererQueue.size == 0) {
        delivererBusy = false
      } else {
        deliverer ! delivererQueue
        delivererQueue = Queue.empty
      }
    }
  }
}

private [core] class ReliableChannelDeliverer(channelId: Int, journal: ActorRef, destination: ActorRef, policy: RedeliveryPolicy) extends Actor {
  import ReliableChannel._

  implicit val executionContext = context.dispatcher
  val scheduler = context.system.scheduler

  var buffer: Option[ActorRef] = None
  var queue = Queue.empty[(Message, ActorRef)]

  var retries = 0
  var currentDelivery: Option[(Message, ActorRef, Cancellable)] = None

  def receive = {
    case Trigger => {
      sender ! FeedMe
    }
    case q: Queue[(Message, ActorRef)] => {
      buffer = Some(sender)
      queue = q
      self ! Next(retries)
    }
    case Next(r) => if (queue.size > 0) {
      val ((msg, sdr), q) = queue.dequeue
      val m = msg.copy(
        posConfirmationTarget = self,
        negConfirmationTarget = self,
        posConfirmationMessage = Confirmed(msg.sequenceNr, true),
        negConfirmationMessage = Confirmed(msg.sequenceNr, false))

      destination tell (m, sdr)

      val task = scheduler.scheduleOnce(Channel.DeliveryTimeout, self, ConfirmationTimeout(m.sequenceNr))

      currentDelivery = Some(msg, sdr, task)
      retries = r
      queue = q
    } else {
      buffer.foreach(_ ! FeedMe)
    }
    case Retry(msg, sdr) => {
      // undo dequeue
      queue = ((msg, sdr) +: queue)
      // and try again ...
      if (retries < policy.retryMax) self ! Next(retries + 1) else buffer.foreach(s => context.stop(s))
    }

    case Confirmed(snr, true) => currentDelivery match {
      case Some((cm, cs, task)) => if (cm.sequenceNr == snr) {
        currentDelivery = None; task.cancel(); journal ! DeleteOutMsg(channelId, snr); self ! Next(0)
      }
      case None => ()
    }
    case Confirmed(snr, false) => currentDelivery match {
      case Some((cm, cs, task)) => if (cm.sequenceNr == snr) {
        currentDelivery = None; task.cancel(); scheduler.scheduleOnce(policy.retryDelay, self, Retry(cm, cs))
      }
      case None => ()
    }
    case ConfirmationTimeout(snr) => currentDelivery match {
      case Some((cm, cs, task)) => if (cm.sequenceNr == snr) {
        currentDelivery = None; scheduler.scheduleOnce(policy.retryDelay, self, Retry(cm, cs))
      }
      case None => ()
    }
  }
}
