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

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util._

import Message._

/**
 * A communication channel used by an event-sourced Component to interact with
 * its environment. A channel is used to communicate via event messages.
 */
trait Channel extends Actor {
  import Journaler._

  def id: Int
  def componentId: Int

  implicit val executionContext = context.dispatcher

  def journaler: ActorRef
  val journalerTimeout = Timeout(10 seconds)

  var counter = 0L

  def journal(cmd: Any): Future[Any] =
    journaler.ask(cmd)(journalerTimeout)

  def lastSequenceNr: Long = {
    val future = journaler.ask(GetLastSequenceNr(componentId, id))(journalerTimeout).mapTo[Long]
    Await.result(future, journalerTimeout.duration)
  }
}

object Channel {
  val inputChannelId = 0

  val destinationTimeout = Timeout(5 seconds)
  val deliveryTimeout = Timeout(5 seconds)
  val redeliveryDelay = 5 seconds

  case class SetProcessor(processor: ActorRef)
  case class SetDestination(processor: ActorRef)

  case object Deliver
  case object Recover
}

/**
 * An input channel is used by application to send event messages to an event-sourced
 * component. This channel writes event messages to a journal before sending it to the
 * component's processor.
 *
 * @param componentId id of the input channel owner
 * @param journaler
 */
class InputChannel(val componentId: Int, val journaler: ActorRef) extends Channel {
  import Channel._
  import Journaler._

  val id = inputChannelId

  var sequencer: Option[ActorRef] = None
  var processor: Option[ActorRef] = None

  def receive = {
    case Message(evt, sdr, sdrmid, _, _, _, false) => {
      val msg = Message(evt, sdr, sdrmid, counter, Nil, Nil)
      val key = Key(componentId, id, msg.sequenceNr, 0)

      val future = journal(WriteMsg(key, msg))

      val s = sender

      future.onSuccess {
        case _ => { sequencer.foreach(_ ! (msg.sequenceNr, msg)); s ! key }
      }

      future.onFailure {
        case e => { context.stop(self); println("journaling failure: %s caused by %s" format (e, msg)) }
        // TODO: inform cluster manager to fail-over
      }

      counter = counter + 1
    }
    case msg @ Message(_, _, _, _, _, _, true) => {
      processor.foreach(_.!(msg.copy(sender = None))(null))
    }
    case cmd @ SetProcessor(p) => {
      sequencer.foreach(_ forward cmd)
      processor = Some(p)
    }
  }

  override def preStart() {
    val lsn = lastSequenceNr
    val seq = context.actorOf(Props(new InputChannelSequencer(lsn)))
    sequencer = Some(seq)
    counter = lsn + 1
  }
}

private class InputChannelSequencer(val lastSequenceNr: Long) extends Sequencer {
  import Channel._

  var processor: Option[ActorRef] = None

  def receiveSequenced = {
    case msg: Message => {
      processor.foreach(_ ! msg)
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
 * to it's environment (or even to the component it is owned by).
 */
trait OutputChannel extends Channel {
  var destination: Option[ActorRef] = None
}

/**
 * An output channel that sends event messages to a destination. If the destination responds
 * with a successful result, a send confirmation is written to the journal.
 *
 * @param componentId id of the input channel owner
 * @param id output channel id (used internally)
 * @param journaler
 */
class DefaultOutputChannel(val componentId: Int, val id: Int, val journaler: ActorRef) extends OutputChannel {
  import Channel._
  import Journaler._
  import Message._

  assert(id > 0)

  def receive = {
    case Message(evt, sdr, sdrmid, seqnr, acks, _, replicated) if (!acks.contains(id) && !replicated) => {
      val msg = Message(evt, sdr, sdrmid, counter, Nil, Nil)

      destination.foreach(_.ask(msg)(destinationTimeout) onSuccess {
        case r => journaler.!(WriteAck(Key(componentId, inputChannelId, seqnr, id)))(null)
      })

      counter = counter + 1
    }
    case Deliver => {
      // nothing to do
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
  }

  override def preStart() {
    counter = 1L
  }
}

/**
 * An output channel that stores output messages in the journal before sending it to its
 * destination. If the destination responds with a successful result the stored output
 * message is removed from the journal, otherwise a re-send is attempted.
 *
 * @param componentId id of the input channel owner
 * @param id output channel id (used internally)
 * @param journaler
 */
class ReliableOutputChannel(val componentId: Int, val id: Int, val journaler: ActorRef, redeliveryDelay: Duration) extends OutputChannel {
  import Channel._
  import Journaler._
  import Message._

  assert(id > 0)

  override val supervisorStrategy = OneForOneStrategy() {
    case e: Exception => SupervisorStrategy.Stop
  }

  var sequencer: Option[ActorRef] = None

  def receive = {
    case Message(evt, sdr, sdrmid, seqnr, acks, _, replicated)  if (!acks.contains(id) && !replicated) => {
      val msg = Message(evt, sdr, sdrmid, counter, Nil, Nil)
      val msgKey = Key(componentId, id, msg.sequenceNr, 0)
      val ackKey = Key(componentId, inputChannelId, seqnr, id)

      val future = journal(WriteAckAndMsg(ackKey, msgKey, msg))

      future.onSuccess {
        case _ => sequencer.foreach(_ ! (msg.sequenceNr, msg))
      }

      future.onFailure {
        case e => { context.stop(self); println("journaling failure: %s caused by %s" format (e, msg)) }
        // TODO: inform cluster manager to fail-over
      }

      counter = counter + 1
    }
    case Deliver => sequencer foreach { s =>
      replayOutputMessages(s)
    }
    case Recover => destination foreach { d =>
      sequencer = Some(createSequencer(d, counter - 1))
      replayOutputMessages(sequencer.get)
    }
    case cmd @ SetDestination(d) => {
      sequencer = Some(createSequencer(d, counter - 1))
      destination = Some(d)
    }
    case Terminated(s) => sequencer foreach { s =>
      sequencer = None
      context.system.scheduler.scheduleOnce(redeliveryDelay, self, Recover)
    }
  }

  def replayOutputMessages(destination: ActorRef) {
    val cmd = Replay(componentId, id, 0L, destination)
    // wait for all stored messages to be added to the destination's mailbox
    Await.result(journaler.ask(cmd)(deliveryTimeout), deliveryTimeout.duration)
  }

  def createSequencer(destination: ActorRef, lastSequenceNr: Long) = {
    context.watch(context.actorOf(Props(new ReliableOutputChannelSequencer(componentId, id, journaler, destination, lastSequenceNr))))
  }

  override def preStart() {
    counter = lastSequenceNr + 1L
  }
}

class ReliableOutputChannelSequencer(componentId: Int, id: Int, journaler: ActorRef, destination: ActorRef, val lastSequenceNr: Long) extends Sequencer {
  import Channel._
  import Journaler._

  def receiveSequenced = {
    case msg: Message => {
      val future = destination.ask(msg)(destinationTimeout)

      future.onSuccess {
        case _ => journaler.!(DeleteMsg(Key(componentId, id, msg.sequenceNr, 0)))(null)
      }

      val ctx = context
      val slf = self

      future.onFailure {
        case _ => ctx.stop(slf)
      }
    }
  }
}
