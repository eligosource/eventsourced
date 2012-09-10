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
 * A communication channel used by an event-sourced component to interact with
 * its environment via event messages.
 */
trait Channel extends Actor {
  def id: Int
  def componentId: Int

  implicit val executionContext = context.dispatcher

  def journal: ActorRef
  val journalTimeout = Timeout(10 seconds)
}

object Channel {
  val inputChannelId = 0

  val destinationTimeout = Timeout(5 seconds)
  val replyDestinationTimeout = Timeout(5 seconds)

  case class SetProcessor(processor: ActorRef)
  case class SetDestination(destination: ActorRef)
  case class SetReplyDestination(replayDestination: ActorRef)

  case object Deliver
}

/**
 * An input channel is used by applications to send event messages to an event-sourced
 * component. This channel writes event messages to a journal before sending it to the
 * component's processor.
 */
class InputChannel(val componentId: Int, val journal: ActorRef) extends Channel {
  import Channel._

  val id = inputChannelId

  var processor: Option[ActorRef] = None

  def receive = {
    case msg: Message => {
      processor foreach { p => journal forward WriteMsg(componentId, id, msg, None, p) }
    }
    case SetProcessor(p) => {
      processor = Some(p)
    }
  }
}

class InputChannelProducer(inputChannel: ActorRef) extends Actor {
  def receive = {
    case msg: Message => inputChannel.!(msg.copy(sender = Some(sender)))(null)
    case evt          => inputChannel.!(Message(evt, Some(sender)))(null)
  }
}

/**
 * A channel used by a component's processor (actor) to send event messages
 * to destinations.
 */
trait OutputChannel extends Channel {
  var destination: Option[ActorRef] = None
  var replyDestination: Option[ActorRef] = None
}

/**
 * An output channel that sends event messages to a destination. If the destination successfully
 * responds, an acknowledgement is written to the journal.
 */
class DefaultOutputChannel(val componentId: Int, val id: Int, val journal: ActorRef) extends OutputChannel {
  import Channel._

  assert(id > 0)

  var retain = true
  var buffer = List.empty[Message]

  def receive = {
    case msg: Message if (!msg.acks.contains(id) && !msg.replicated) => {
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
      r1 <- d.ask(msg)(destinationTimeout)
      r2 <- reply(r1)
    } yield r2

    r onSuccess {
      case _ if (msg.ack) => journal.!(WriteAck(componentId, id, msg.sequenceNr))(null)
    }
  }

  def reply(msg: Any): Future[Any] =
    replyDestination.map(_.ask(msg)(replyDestinationTimeout)).getOrElse(Promise.successful(Ack))
}

case class ReliableOutputChannelEnv(
  componentId: Int,
  journal: ActorRef,
  recoveryDelay: Duration,
  retryDelay: Duration,
  retryMax: Int
)

/**
 * An output channel that stores output messages in the journal before sending it to a
 * destination. If the destination successfully responds, the stored output message is
 * removed from the journal, otherwise a re-delivery is attempted.
 */
class ReliableOutputChannel(val id: Int, env: ReliableOutputChannelEnv) extends OutputChannel {
  import Channel._

  assert(id > 0)

  val componentId = env.componentId
  val journal = env.journal

  var buffer: Option[ActorRef] = None

  def receive = {
    case msg: Message if (!msg.acks.contains(id) && !msg.replicated) => {
      val ackSequenceNr = if (msg.ack) Some(msg.sequenceNr) else None
      journal.!(WriteMsg(componentId, id, msg, ackSequenceNr, buffer.getOrElse(context.system.deadLetters)))(null)
    }
    case Deliver => destination foreach { d =>
      buffer = Some(createBuffer(d))
      deliverPendingMessages(buffer.get)
    }
    case Terminated(s) => buffer foreach { b =>
      buffer = None
      context.system.scheduler.scheduleOnce(env.recoveryDelay, self, Deliver)
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
    case SetReplyDestination(rd) => {
      replyDestination = Some(rd)
    }
  }

  def deliverPendingMessages(destination: ActorRef) {
    journal.!(ReplayOutput(componentId, id, 0L, destination))(null)
  }

  def createBuffer(destination: ActorRef) = {
    context.watch(context.actorOf(Props(new ReliableOutputChannelBuffer(id, destination, replyDestination, env))))
  }
}

object ReliableOutputChannel {
  val defaultReplayTimeout = Timeout(5 seconds)
  val defaultRecoveryDelay = 5 seconds
  val defaultRetryDelay = 1 second
  val defaultRetryMax = 3

  case class Next(retries: Int)
  case class Retry(msg: Message)

  case object Trigger
  case object FeedMe
}

class ReliableOutputChannelBuffer(channelId: Int, destination: ActorRef, replyDestination: Option[ActorRef], env: ReliableOutputChannelEnv) extends Actor {
  import ReliableOutputChannel._

  var rocSenderQueue = Queue.empty[Message]
  var rocSenderBusy = false //

  val rocSender = context.actorOf(Props(new ReliableOutputChannelSender(channelId, destination, replyDestination, env)))

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

class ReliableOutputChannelSender(channelId: Int, destination: ActorRef, replyDestination: Option[ActorRef], env: ReliableOutputChannelEnv) extends Actor {
  import Channel._
  import ReliableOutputChannel._

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
          env.journal.!(DeleteMsg(env.componentId, channelId, msg.sequenceNr))(null)
          self ! Next(0)
        }
      }

      future onFailure {
        case e => ctx.system.scheduler.scheduleOnce(env.retryDelay, self, Retry(msg))
      }
    } else {
      sequencer.foreach(_ ! FeedMe)
    }
    case Retry(msg) => {
      // undo dequeue
      queue = (msg +: queue)
      // and try again ...
      if (retries < env.retryMax) self ! Next(retries + 1) else sequencer.foreach(s => context.stop(s))
    }
  }

  def send(msg: Message): Future[Any] = for {
    r1 <- destination.ask(msg)(destinationTimeout)
    r2 <- reply(r1)
  } yield r2

  def reply(msg: Any): Future[Any] =
    replyDestination.map(_.ask(msg)(replyDestinationTimeout)).getOrElse(Promise.successful(Ack))
}
