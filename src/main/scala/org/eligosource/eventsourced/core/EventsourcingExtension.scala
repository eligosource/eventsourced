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
import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

/**
 * Event-sourcing extension for Akka. Used by applications to create and register
 * [[org.eligosource.eventsourced.core.Eventsourced]] processors and event message
 * [[org.eligosource.eventsourced.core.Channel]]s and to recover them from journaled
 * event [[org.eligosource.eventsourced.core.Message]]s.
 *
 * @param system actor system this extension is associated with.
 */
class EventsourcingExtension(system: ActorSystem) extends Extension {
  private val journalRef = new AtomicReference[Option[ActorRef]](None)
  private val channelsRef = new AtomicReference[ChannelMappings](ChannelMappings())
  private val processorsRef = new AtomicReference[Map[Int, ActorRef]](Map.empty)

  /**
   * Journal for this extension.
   */
  def journal: ActorRef =
    journalRef.get.getOrElse(throw new IllegalStateException("no journal registered"))

  /**
   * Map of registered [[org.eligosource.eventsourced.core.Channel]]s. Mapping key is
   * the channel id.
   */
  def channels: Map[Int, ActorRef] =
    channelsRef.get.idToChannel

  /**
   * Map of registered named [[org.eligosource.eventsourced.core.Channel]]s. Mapping key is
   * the channel name.
   */
  def namedChannels: Map[String, ActorRef] =
    channelsRef.get.nameToChannel

  /**
   * Map of registered [[org.eligosource.eventsourced.core.Eventsourced]] processors.
   * Mapping key is the processor id.
   */
  def processors: Map[Int, ActorRef] =
    processorsRef.get

  /**
   * Replays input messages to selected processors. This method does not wait for
   * replayed input messages being processed. However, any new message sent to any
   * of the selected processors, after this method returned, is guaranteed to be
   * processed after the replayed event messages.
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
   * Enables the channel registered under `channelId` and starts delivery of pending messages.
   */
  def deliver(channelId: Int) {
    journal ! BatchDeliverOutMsgs(channels.get(channelId).toList)
  }

  /**
   * Recovers all processors and channels registered at this extension by first
   * calling `replay(_ => Some(0))` and then `deliver()`. Replay is done with no
   * lower bound (i.e. with all messages in the journal) for all processors of
   * this context.
   *
   * This method does not wait for replayed input messages being processed. However,
   * any new message sent to any of the processors, after this method returned, is
   * guaranteed to be processed after the replayed event messages.
   */
  def recover() {
    recover(_ => Some(0))
  }

  /**
   * Recovers selected processors and all channels registered at this extension by first
   * calling `replay(f)` and then `deliver()`.
   *
   * This method does not wait for replayed input messages being processed. However,
   * any new message sent to any of the selected processors, after this method returned,
   * is guaranteed to be processed after the replayed event messages.
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
   * @param props processor configuration object.
   * @return a processor ref.
   * @throws InvalidActorNameException if `props.name` is defined and already
   *         in use in the underlying actor system.
   *
   * @see [[org.eligosource.eventsourced.core.ProcessorProps]]
   */
  def processorOf(props: ProcessorProps)(implicit actorRefFactory: ActorRefFactory): ActorRef = {
    val processor = props.createProcessor()
    registerProcessor(props.id, processor)
    processor
  }

  /**
   * Registers an [[org.eligosource.eventsourced.core.Eventsourced]] processor.
   *
   * This method obtains the id from the created processor with a blocking operation.
   * Use the overloaded `processorOf(ProcessorProps)` method if you want to avoid
   * blocking.
   *
   * @param props actor ref configuration object.
   * @param name optional processor name.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Eventsourced]]
   *        ref factory.
   * @return a processor ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def processorOf(props: Props, name: Option[String] = None)(implicit actorRefFactory: ActorRefFactory): ActorRef = {
    implicit val duration = 5 seconds

    val processor = if (name.isDefined)
      actorRefFactory.actorOf(props, name.get) else
      actorRefFactory.actorOf(props)
    val future = processor.ask(Eventsourced.GetId)(Timeout(duration)).mapTo[Int]
    val id = Await.result(future, duration)

    registerProcessor(id, processor)
    processor
  }

  /**
   * Creates and registers a [[org.eligosource.eventsourced.core.Channel]]. The channel is
   * registered under the id specified by `props.id`. If `props.name` is defined it is also
   * registered under that name.
   *
   * @param props channel configuration object.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Channel]] ref factory.
   * @return a channel ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   *
   * @see [[org.eligosource.eventsourced.core.DefaultChannelProps]]
   *      [[org.eligosource.eventsourced.core.ReliableChannelProps]]
   */
  def channelOf(props: ChannelProps)(implicit actorRefFactory: ActorRefFactory): ActorRef = {
    val channel = props.createChannel(journal)
    registerChannel(props.id, props.name, channel)
    channel
  }

  /**
   * Waits for selected processors to complete processing of all pending messages
   * in their mailboxes.
   *
   * @param processorIds ids of registered processors to wait for. Default value
   *        is the set of all registered processor ids.
   */
  def awaitProcessorCompletion(processorIds: Set[Int] = processors.keySet)(implicit timeout: Timeout) {
    import Eventsourced._
    import system.dispatcher

    val selected = processors.filter { case (k, _) => processorIds.contains(k) }.values
    val future = Future.sequence(selected.map(p => p.ask(AwaitCompletion)))

    Await.result(future, timeout.duration)
  }

  @tailrec
  private def registerChannel(channelId: Int, channelName: Option[String], channel: ActorRef): Unit = {
    val current = channelsRef.get()
    val updated = if (channelName.isDefined) current.add(channelId, channelName.get, channel) else current.add(channelId, channel)
    if (!channelsRef.compareAndSet(current, updated)) registerChannel(channelId, channelName, channel)
  }

  @tailrec
  private def registerProcessor(processorId: Int, processor: ActorRef): Unit = {
    val current = processorsRef.get()
    val updated = current + (processorId -> processor)
    if (!processorsRef.compareAndSet(current, updated)) registerProcessor(processorId, processor)
  }

  @tailrec
  private [core] final def deregisterChannel(channelId: Int): Unit = {
    val current = channelsRef.get()
    val updated = current.remove(channelId)
    if (!channelsRef.compareAndSet(current, updated)) deregisterChannel(channelId)
  }

  @tailrec
  private [core] final def deregisterProcessor(processorId: Int): Unit = {
    val current = processorsRef.get()
    val updated = current - processorId
    if (!processorsRef.compareAndSet(current, updated)) deregisterProcessor(processorId)
  }

  private [core] def registerJournal(journal: ActorRef) {
    journalRef.set(Some(journal))
  }

  private case class ChannelMappings(
    idToChannel: Map[Int, ActorRef] = Map.empty,
    idToName: Map[Int, String] = Map.empty,
    nameToChannel: Map[String, ActorRef] = Map.empty) {

    def add(id: Int, channel: ActorRef): ChannelMappings = copy(
      idToChannel + (id -> channel),
      idToName,
      nameToChannel)

    def add(id: Int, name: String, channel: ActorRef): ChannelMappings = copy(
      idToChannel + (id -> channel),
      idToName + (id -> name),
      nameToChannel + (name -> channel)
    )

    def remove(id: Int) = copy(
      idToChannel - id,
      idToName - id,
      if (idToName.contains(id)) nameToChannel - idToName(id) else nameToChannel
    )
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
 * @param id Processor id.
 * @param processorFactory Processor factory.
 * @param name Optional processor name.
 */
case class ProcessorProps(
  id: Int,
  processorFactory: Int => Actor with Eventsourced,
  name: Option[String] = None) {

  /**
   * Creates a processor with the settings defined by this configuration object.
   *
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Eventsourced]]
   *        ref factory.
   * @return a processor ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def createProcessor()(implicit actorRefFactory: ActorRefFactory): ActorRef = {
    if (name.isDefined)
      actorRefFactory.actorOf(Props(processorFactory(id)), name.get) else
      actorRefFactory.actorOf(Props(processorFactory(id)))
  }
}

/**
 * Channel configuration object.
 */
trait ChannelProps {
  /** Channel id. */
  def id: Int
  /** Optional channel name. */
  def name: Option[String]
  /** Channel destination. */
  def destination: ActorRef

  /**
   * Creates a channel with the settings defined by this configuration object.
   *
   * @param journal journal that is used by the channel.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Channel]] ref factory.
   * @return a channel ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory): ActorRef
}

/**
 * [[org.eligosource.eventsourced.core.DefaultChannel]] configuration object.
 */
case class DefaultChannelProps(
    id: Int,
    destination: ActorRef,
    name: Option[String] = None) extends ChannelProps {

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
    if (name.isDefined)
      actorRefFactory.actorOf(Props(new DefaultChannel(id, journal, destination)), name.get) else
      actorRefFactory.actorOf(Props(new DefaultChannel(id, journal, destination)))
  }
}

/**
 * [[org.eligosource.eventsourced.core.ReliableChannel]] configuration object.
 */
case class ReliableChannelProps(
    id: Int,
    destination: ActorRef,
    policy: RedeliveryPolicy = RedeliveryPolicy(),
    name: Option[String] = None) extends ChannelProps {

  /**
   * Returns a new `ReliableChannelProps` with the specified name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `ReliableChannelProps` with the specified recovery delay.
   */
  def withRecoveryDelay(recoveryDelay: FiniteDuration) =
    copy(policy = policy.copy(recoveryDelay = recoveryDelay))

  /**
   * Returns a new `ReliableChannelProps` with the specified retry delay.
   */
  def withRetryDelay(retryDelay: FiniteDuration) =
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
    if (name.isDefined)
      actorRefFactory.actorOf(Props(new ReliableChannel(id, journal, destination, policy)), name.get) else
      actorRefFactory.actorOf(Props(new ReliableChannel(id, journal, destination, policy)))
  }
}
