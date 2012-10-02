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

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

import akka.actor._
import akka.util.Duration

object EventsourcingExtension extends ExtensionId[EventsourcingExtension] with ExtensionIdProvider {
  def apply(system: ActorSystem, journal: ActorRef): EventsourcingExtension = {
    val extension = super.apply(system)
    extension.registerJournal(journal)
    extension
  }

  def createExtension(system: ExtendedActorSystem) =
    new EventsourcingExtension(system)

  def lookup() = EventsourcingExtension
}

class EventsourcingExtension(system: ActorSystem) extends Extension {
  private val journalRef = new AtomicReference[Option[ActorRef]](None)
  private val channelsRef = new AtomicReference[Map[String, ActorRef]](Map.empty)
  private val processorsRef = new AtomicReference[Map[Int, ActorRef]](Map.empty)

  private [core] val producer: ActorRef = system.actorOf(Props[Producer])

  def journal: ActorRef =
    journalRef.get.getOrElse(throw new IllegalStateException("no journal registered"))

  def channels: Map[String, ActorRef] =
    channelsRef.get

  def processors: Map[Int, ActorRef] =
    processorsRef.get

  /**
   * Replays input messages to processors.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay is done for that processor. If it returns `Some(snr)`
   *        message replay starts from sequence number `snr` for that processor.
   */
  def replay(f: (Int) => Option[Long]) {
    val replays = processors.collect { case kv if (f(kv._1).isDefined) => ReplayInMsgs(kv._1, f(kv._1).get, kv._2) }
    journal ! BatchReplayInMsgs(replays.toList)
  }

  def deliver() {
    journal ! BatchDeliverOutMsgs(channels.values.toList)
  }

  def recover() {
    recover(_ => Some(0))
  }

  def recover(f: (Int) => Option[Long]) {
    replay(f)
    deliver()
  }

  def processorOf(props: ProcessorProps): ActorRef = {
    registerProcessor(props.id, props.processor)
    props.processor
  }

  def channelOf(props: ChannelProps)(implicit actorRefFactory: ActorRefFactory): ActorRef = {
    val channel = props.createChannel(journal)
    registerChannel(props.name.getOrElse(props.id.toString), channel)
    channel
  }

  @tailrec
  private def registerChannel(channelName: String, channel: ActorRef): Unit = {
    val current = channelsRef.get()
    val updated = current + (channelName -> channel)
    if (!channelsRef.compareAndSet(current, updated)) registerChannel(channelName, channel)
  }

  @tailrec
  private def registerProcessor(processorId: Int, processor: ActorRef): Unit = {
    val current = processorsRef.get()
    val updated = current + (processorId -> processor)
    if (!processorsRef.compareAndSet(current, updated)) registerProcessor(processorId, processor)
  }

  private [core] def registerJournal(journal: ActorRef) {
    journalRef.set(Some(journal))
  }
}

case class ProcessorProps(id: Int, processor: ActorRef)

object ProcessorProps {
  def apply(id: Int, processorFactory: => Eventsourced)(implicit actorRefFactory: ActorRefFactory): ProcessorProps = {
    val processor = actorRefFactory.actorOf(Props(processorFactory))
    processor ! Eventsourced.SetId(id)
    new ProcessorProps(id, processor)
  }
}

trait ChannelProps {
  def id: Int
  def name: Option[String]
  def destination: ActorRef
  def replyDestination: Option[ActorRef]
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory): ActorRef
}

case class DefaultChannelProps(
    id: Int,
    destination: ActorRef,
    replyDestination: Option[ActorRef] = None,
    name: Option[String] = None) extends ChannelProps {

  def withReplyDestination(replyDestination: ActorRef) =
    copy(replyDestination = Some(replyDestination))

  def withName(name: String) =
    copy(name = Some(name))

  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) = {
    val channel = actorRefFactory.actorOf(Props(new DefaultChannel(id, journal)))
    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))
    channel
  }
}

case class ReliableChannelProps(
    id: Int,
    destination: ActorRef,
    replyDestination: Option[ActorRef] = None,
    conf: ReliableChannelConf = ReliableChannelConf(),
    name: Option[String] = None) extends ChannelProps {

  def withReplyDestination(replyDestination: ActorRef) =
    copy(replyDestination = Some(replyDestination))

  def withName(name: String) =
    copy(name = Some(name))

  def withRecoveryDelay(recoveryDelay: Duration) =
    copy(conf = conf.copy(recoveryDelay = recoveryDelay))

  def withRetryDelay(retryDelay: Duration) =
    copy(conf = conf.copy(retryDelay = retryDelay))

  def withRetryMax(retryMax: Int) =
    copy(conf = conf.copy(retryMax = retryMax))

  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) = {
    val channel = actorRefFactory.actorOf(Props(new ReliableChannel(id, journal, conf)))
    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))
    channel
  }
}
