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

/**
 * Event-sourcing extension for Akka. Used by applications to create and register
 * [[org.eligosource.eventsourced.core.Eventsourced]] processors and event message
 * [[org.eligosource.eventsourced.core.Channel]]s and to recover them from journaled
 * event messages.
 *
 * @param system actor system this extension is associated with.
 */
class EventsourcingExtension(system: ActorSystem) extends Extension {
  private val journalRef = new AtomicReference[Option[ActorRef]](None)
  private val channelsRef = new AtomicReference[Map[String, ActorRef]](Map.empty)
  private val processorsRef = new AtomicReference[Map[Int, ActorRef]](Map.empty)

  private [core] val producer: ActorRef = system.actorOf(Props[Producer])

  /**
   * Journal for this extension. A journal should not be shared between extensions.
   */
  def journal: ActorRef =
    journalRef.get.getOrElse(throw new IllegalStateException("no journal registered"))

  /**
   * Map of registered [[org.eligosource.eventsourced.core.Channel]]s. Mapping key is
   * the channel name.
   */
  def channels: Map[String, ActorRef] =
    channelsRef.get

  /**
   * Map of registered [[org.eligosource.eventsourced.core.Eventsourced]] processors.
   * Mapping key is the processor id.
   */
  def processors: Map[Int, ActorRef] =
    processorsRef.get

  /**
   * Replays input messages to selected processors.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay will be done for that processor. If it returns `Some(snr)`
   *        message replay will start from sequence number `snr` for that processor.
   */
  def replay(f: (Int) => Option[Long]) {
    val replays = processors.collect { case kv if (f(kv._1).isDefined) => ReplayInMsgs(kv._1, f(kv._1).get, kv._2) }
    journal ! BatchReplayInMsgs(replays.toList)
  }

  /**
   * Enables all registered channels and starts delivery of pending messages.
   */
  def deliver() {
    journal ! BatchDeliverOutMsgs(channels.values.toList)
  }

  /**
   * Enables the channel registered under `channelName` and starts delivery of pending messages.
   */
  def deliver(channelName: String) {
    journal ! BatchDeliverOutMsgs(channels.get(channelName).toList)
  }

  /**
   * Recovers all processors and channels registered at this extension by first
   * calling `replay(_ => Some(0))` and then `deliver()`. Replay is done with no
   * lower bound (i.e. with all messages in the journal) for all processors of
   * this context.
   */
  def recover() {
    recover(_ => Some(0))
  }

  /**
   * Recovers selected processors and all channels registered at this extension by first
   * calling `replay(f)` and then `deliver()`.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay will be done for that processor. If it returns `Some(snr)`
   *        message replay will start from sequence number `snr` for that processor.
   */
  def recover(f: (Int) => Option[Long]) {
    replay(f)
    deliver()
  }

  /**
   * Registers an [[org.eligosource.eventsourced.core.Eventsourced]] processor.
   *
   * @param props processor configuration object
   * @return a processor ref
   *
   * @see [[org.eligosource.eventsourced.core.ProcessorProps]]
   */
  def processorOf(props: ProcessorProps): ActorRef = {
    registerProcessor(props.id, props.processor)
    props.processor
  }

  /**
   * Creates and registers a [[org.eligosource.eventsourced.core.Channel]]. The channel is
   * registered under the name given by `props.name`.
   *
   * @param props channel configuration object.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Channel]] ref factory.
   * @return a channel ref.
   *
   * @see [[org.eligosource.eventsourced.core.DefaultChannelProps]]
   *      [[org.eligosource.eventsourced.core.ReliableChannelProps]]
   */
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

/**
 * Event-sourcing extension access point.
 */
object EventsourcingExtension extends ExtensionId[EventsourcingExtension] with ExtensionIdProvider {

  /**
   * Obtains the `EventsourcingExtension` instance associated with `system`, registers a `journal`
   * at that instance and returns it.
   *
   * @param system actor system associated with the returned extension instance.
   * @param journal journal to register.
   */
  def apply(system: ActorSystem, journal: ActorRef): EventsourcingExtension = {
    val extension = super.apply(system)
    extension.registerJournal(journal)
    extension
  }

  def createExtension(system: ExtendedActorSystem) =
    new EventsourcingExtension(system)

  def lookup() = EventsourcingExtension
}

/**
 * [[org.eligosource.eventsourced.core.Eventsourced]] processor configuration object.
 *
 * @param id processor id.
 * @param processor processor ref.
 */
case class ProcessorProps(id: Int, processor: ActorRef)

object ProcessorProps {
  /**
   * Creates a processor configuration object from a processor id and processor factory.
   *
   * @param id processor id.
   * @param processorFactory [[org.eligosource.eventsourced.core.Eventsourced]] processor factory.
   * @param actorRefFactory  [[org.eligosource.eventsourced.core.Eventsourced]] processor ref factory.
   * @return processor configuration object containing the created processor ref.
   */
  def apply(id: Int, processorFactory: => Eventsourced)(implicit actorRefFactory: ActorRefFactory): ProcessorProps = {
    val processor = actorRefFactory.actorOf(Props(processorFactory))
    processor ! Eventsourced.SetId(id)
    new ProcessorProps(id, processor)
  }
}

/**
 * Channel configuration object.
 */
trait ChannelProps {
  /** Channel id. */
  def id: Int
  /** Optional channel name */
  def name: Option[String]
  /** Channel destination */
  def destination: ActorRef
  /** Optional reply destination */
  def replyDestination: Option[ActorRef]

  /**
   * Creates a channel with the settings defined by this configuration object.
   *
   * @param journal journal that is used by the channel.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Channel]] ref factory.
   * @return a channel ref.
   */
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory): ActorRef
}

/**
 * [[org.eligosource.eventsourced.core.DefaultChannel]] configuration object.
 */
case class DefaultChannelProps(
    id: Int,
    destination: ActorRef,
    replyDestination: Option[ActorRef] = None,
    name: Option[String] = None) extends ChannelProps {

  /**
   * Returns a new `DefaultChannelProps` with the specified reply destination.
   */
  def withReplyDestination(replyDestination: ActorRef) =
    copy(replyDestination = Some(replyDestination))

  /**
   * Returns a new `DefaultChannelProps` with the specified name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Creates a [[org.eligosource.eventsourced.core.DefaultChannel]] with the
   * settings defined by this configuration object.
   */
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) = {
    val channel = actorRefFactory.actorOf(Props(new DefaultChannel(id, journal)))
    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))
    channel
  }
}

/**
 * [[org.eligosource.eventsourced.core.ReliableChannel]] configuration object.
 */
case class ReliableChannelProps(
    id: Int,
    destination: ActorRef,
    replyDestination: Option[ActorRef] = None,
    policy: RedeliveryPolicy = RedeliveryPolicy(),
    name: Option[String] = None) extends ChannelProps {

  /**
   * Returns a new `ReliableChannelProps` with the specified reply destination.
   */
  def withReplyDestination(replyDestination: ActorRef) =
    copy(replyDestination = Some(replyDestination))

  /**
   * Returns a new `ReliableChannelProps` with the specified name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `ReliableChannelProps` with the specified recovery delay.
   */
  def withRecoveryDelay(recoveryDelay: Duration) =
    copy(policy = policy.copy(recoveryDelay = recoveryDelay))

  /**
   * Returns a new `ReliableChannelProps` with the specified retry delay.
   */
  def withRetryDelay(retryDelay: Duration) =
    copy(policy = policy.copy(retryDelay = retryDelay))

  /**
   * Returns a new `ReliableChannelProps` with the specified maximum number of re-delivery attempts.
   */
  def withRetryMax(retryMax: Int) =
    copy(policy = policy.copy(retryMax = retryMax))

  /**
   * Creates a [[org.eligosource.eventsourced.core.ReliableChannel]] with the
   * settings defined by this configuration object.
   */
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) = {
    val channel = actorRefFactory.actorOf(Props(new ReliableChannel(id, journal, policy)))
    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))
    channel
  }
}
