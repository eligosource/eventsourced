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
package org.eligosource.eventsourced.core

import scala.concurrent.duration._
import scala.collection.immutable.Queue

import akka.actor._

import org.eligosource.eventsourced.core.Journal._

/**
 * A channel keeps track of successfully delivered event [[org.eligosource.eventsourced.core.Message]]s.
 * Channels are used by [[org.eligosource.eventsourced.core.Eventsourced]] actors to prevent redundant
 * message delivery to destinations during event message replay.
 *
 * A less reliable alternative to channels is communication via sender references. Event messages that
 * are sent to processors during a replay always have a `deadLetters` sender reference which prevents
 * redundant delivery as well. The main difference is that the delivery guarantee changes from
 * ''at-least-once'' to ''at-most-once''.
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

object Channel {

  /**
   * Channel command for starting delivery of pending event messages.
   */
  case object Deliver

  /**
   * Channel event that is published when a reliable channel (identified by `channelId`)
   * stops event message delivery. The event is published to the event stream of the
   * [[akka.actor.ActorSystem]] the reliable channel belongs to.
   *
   * @param channelId id of the reliable channel that stopped event message
   *        delivery.
   *
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  case class DeliveryStopped(channelId: Int)
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
 * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
 */
class DefaultChannel(val id: Int, val journal: ActorRef, val destination: ActorRef) extends Channel {
  import Channel.Deliver

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
 * @param confirmationTimeout Duration to wait for a confirmation. If that duration is
 *        exceeded a re-delivery is attempted.
 * @param restartDelay Delay for re-starting a reliable channel that has been stopped
 *        after having reached the maximum number of re-delivery attempts.
 * @param restartMax Maximum number of re-starts.
 * @param redeliveryDelay Delay between re-delivery attempts.
 * @param redeliveryMax Maximum number of re-delivery attempts.
 */
case class RedeliveryPolicy(
  confirmationTimeout: FiniteDuration,
  restartDelay: FiniteDuration,
  restartMax: Int,
  redeliveryDelay: FiniteDuration,
  redeliveryMax: Int)

object RedeliveryPolicy {
  /** Default confirmation timeout: 5 seconds */
  val DefaultConfirmationTimeout = 5 seconds
  /** Default restart delay: 5 seconds */
  val DefaultRestartDelay = 10 seconds
  //** Default maximum number of re-starts: 1 */
  val DefaultRestartMax = 1
  /** Default re-delivery delay: 1 second */
  val DefaultRedeliveryDelay = 1 second
  //** Default maximum number of re-deliveries: 3 */
  val DefaultRedeliveryMax = 3

  /**
   * Returns a [[org.eligosource.eventsourced.core.RedeliveryPolicy]] with default settings.
   */
  def apply() = new RedeliveryPolicy(
    DefaultConfirmationTimeout,
    DefaultRestartDelay,
    DefaultRestartMax,
    DefaultRedeliveryDelay,
    DefaultRedeliveryMax)
}

/**
 * A persistent channel that sends event [[org.eligosource.eventsourced.core.Message]]s to
 * `destination`. Every event message sent to this channel is stored in the journal together
 * with an ''acknowledgement'' (which is used during replay to decide if the channel should
 * ignore a message or not).
 *
 * If `destination` positively confirms the receipt of an event message with `Message.confirm()`
 * the stored message is deleted from the journal. If `destination` negatively confirms the
 * receipt of an event message with `Message.confirm(false)` or no confirmation is made (i.e.
 * a timeout occurs), a re-delivery attempt is made after a certain ''redelivery delay''
 * (specified by `policy.redeliveryDelay`).
 *
 * If the maximum number of re-delivery attempts have been made (specified by `policy.redeliveryMax`),
 * the channel restarts itself after a certain ''restart delay'' (specified by `policy.restartDelay`)
 * and starts again with re-deliveries. If the maximum number of restarts has been reached (specified
 * by `policy.restartMax`) the channel stops message delivery and publishes a
 * [[org.eligosource.eventsourced.core.Channel.DeliveryStopped]] event to the event stream of the
 * [[akka.actor.ActorSystem]] this channel belongs to. Applications can then re-activate the channel
 * by calling `EventsourcingExtension.deliver(Int)` with the channel id as argument.
 *
 * A `ReliableChannel` stores `sender` references along with event messages. A destination can even
 * reply to a sender that was sending an event message in a previous application run (e.g. before the
 * application crashed). If that sender doesn't exist any more after recovery, the reply will go to
 * `deadLetters`.
 *
 * @param id channel id. Must be a positive integer.
 * @param journal journal of the [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 *        at which this channel is registered.
 * @param destination delivery destination of event messages added to this channel.
 * @param policy redelivery policy.
 * @param dispatcherName optional dispatcher name.
 *
 * @see [[org.eligosource.eventsourced.core.Channel]]
 * @see [[org.eligosource.eventsourced.core.RedeliveryPolicy]]
 * @see [[org.eligosource.eventsourced.core.Journal.WriteOutMsg]]
 * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
 */
class ReliableChannel(val id: Int, val journal: ActorRef, val destination: ActorRef, policy: RedeliveryPolicy, dispatcherName: Option[String] = None) extends Channel {
  import ReliableChannel._
  import Channel._


  require(id > 0, "channel id must be a positive integer")

  private var buffer: Option[ActorRef] = None
  private var restarts = 0

  def receive = {
    case msg: Message if (!msg.acks.contains(id)) => {
      val ackSequenceNr: Long = if (msg.ack) msg.sequenceNr else SkipAck
      val senderPath = sender.path.toString
      journal forward WriteOutMsg(id, msg.copy(senderPath = senderPath), msg.processorId, ackSequenceNr, buffer.getOrElse(context.system.deadLetters))
    }
    case Deliver => if (!buffer.isDefined) {
      buffer = Some(createBuffer())
      deliverPendingMessages(buffer.get)
    }
    case ResetRestartCounter => {
      restarts = 0
    }
    case Terminated(s) => buffer foreach { b =>
      buffer = None
      if (restarts < policy.restartMax) {
        restarts += 1
        context.system.scheduler.scheduleOnce(policy.restartDelay, self, Deliver)
      } else {
        restarts = 0
        context.system.eventStream.publish(DeliveryStopped(id))
      }
    }
  }

  private def deliverPendingMessages(dst: ActorRef) {
    journal ! ReplayOutMsgs(id, 0L, dst)
  }

  private def createBuffer() =
    context.watch(actor(new ReliableChannelBuffer(id, journal, destination, policy, dispatcherName), dispatcherName = dispatcherName))
}

private [core] object ReliableChannel {
  case class Buffered(queue: Queue[Message])
  case class Next(retries: Int)
  case class Retry(msg: Message, sdr: ActorRef)

  case object Trigger
  case object FeedMe
  case object ResetRestartCounter

  case class Confirmed(snr: Long, pos: Boolean = true)
  case class ConfirmationTimeout(snr: Long)
}

private [core] class ReliableChannelBuffer(channelId: Int, journal: ActorRef, destination: ActorRef, policy: RedeliveryPolicy, dispatcherName: Option[String]) extends Actor {
  import ReliableChannel._

  var delivererQueue = Queue.empty[Message]
  var delivererBusy = false

  val deliverer = createDeliverer()

  def receive = {
    case Written(msg) => {
      delivererQueue = delivererQueue.enqueue(msg)
      if (!delivererBusy) {
        delivererBusy = true
        deliverer ! Trigger
      }
    }
    case FeedMe => {
      if (delivererQueue.size == 0) {
        delivererBusy = false
      } else {
        deliverer ! Buffered(delivererQueue)
        delivererQueue = Queue.empty
      }
    }
  }

  def createDeliverer() =
    actor(new ReliableChannelDeliverer(channelId, context.parent, journal, destination, policy), dispatcherName = dispatcherName)
}

private [core] class ReliableChannelDeliverer(channelId: Int, channel: ActorRef, journal: ActorRef, destination: ActorRef, policy: RedeliveryPolicy) extends Actor {
  import ReliableChannel._

  implicit val executionContext = context.dispatcher
  val scheduler = context.system.scheduler

  var buffer: Option[ActorRef] = None
  var queue = Queue.empty[Message]

  var delivered = false
  var redeliveries = 0
  var currentDelivery: Option[(Message, ActorRef, Cancellable)] = None

  def receive = {
    case Trigger => {
      sender ! FeedMe
    }
    case Buffered(q) => {
      buffer = Some(sender)
      queue = q
      self ! Next(redeliveries)
    }
    case Next(r) => if (queue.size > 0) {
      val (msg, q) = queue.dequeue
      val m = msg.copy(
        senderPath = null,
        posConfirmationTarget = self,
        negConfirmationTarget = self,
        posConfirmationMessage = Confirmed(msg.sequenceNr, true),
        negConfirmationMessage = Confirmed(msg.sequenceNr, false))

      val sdr = if (msg.senderPath == null) context.system.deadLetters else context.actorFor(msg.senderPath)

      destination tell (m, sdr)

      val task = scheduler.scheduleOnce(policy.confirmationTimeout, self, ConfirmationTimeout(m.sequenceNr))

      currentDelivery = Some(msg, sdr, task)
      redeliveries = r
      queue = q
    } else {
      buffer.foreach(_ ! FeedMe)
    }
    case Retry(msg, sdr) => {
      // undo dequeue
      queue = (msg +: queue)
      // and try again ...
      if (redeliveries < policy.redeliveryMax) self ! Next(redeliveries + 1) else {
        if (delivered) channel ! ResetRestartCounter
        buffer.foreach(b => context.stop(b))
      }
    }

    case Confirmed(snr, true) => currentDelivery match {
      case Some((cm, cs, task)) => if (cm.sequenceNr == snr) {
        currentDelivery = None; task.cancel(); journal ! DeleteOutMsg(channelId, snr); self ! Next(0); redeliveries = 0; delivered = true
      }
      case None => ()
    }
    case Confirmed(snr, false) => currentDelivery match {
      case Some((cm, cs, task)) => if (cm.sequenceNr == snr) {
        currentDelivery = None; task.cancel(); scheduler.scheduleOnce(policy.redeliveryDelay, self, Retry(cm, cs))
      }
      case None => ()
    }
    case ConfirmationTimeout(snr) => currentDelivery match {
      case Some((cm, cs, task)) => if (cm.sequenceNr == snr) {
        currentDelivery = None; scheduler.scheduleOnce(policy.redeliveryDelay, self, Retry(cm, cs))
      }
      case None => ()
    }
  }
}
