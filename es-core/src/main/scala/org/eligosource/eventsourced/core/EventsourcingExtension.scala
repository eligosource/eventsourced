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

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

import akka.actor._
import akka.pattern._
import akka.util.Timeout

import org.eligosource.eventsourced.core.Journal._

/**
 * Event-sourcing extension for Akka. Used by applications to create and register
 * [[org.eligosource.eventsourced.core.Eventsourced]] processors and event message
 * [[org.eligosource.eventsourced.core.Channel]]s and to recover them from journaled
 * event [[org.eligosource.eventsourced.core.Message]]s.
 *
 * @param system actor system this extension is associated with.
 */
class EventsourcingExtension(system: ExtendedActorSystem) extends Extension {
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
   * Replays input messages to selected processors. The returned `Future` will be
   * completed when the replayed messages have been added to the selected processor's
   * mailboxes. Any new message sent to any of the selected processors, after successful
   * completion of the returned `Future`, will be processed after the replayed input
   * messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after the returned `Future` successfully completed.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay will be done for that processor. If it returns `Some(snr)`
   *        message replay will start from sequence number `snr` for that processor.
   */
  def replay(f: (Int) => Option[Long])(implicit timeout: Timeout): Future[Any] = {
    val replays = processors.collect {
      case (pid, p) if (f(pid).isDefined) => ReplayInMsgs(pid, f(pid).get, p)
    }
    journal ? (BatchReplayInMsgs(replays.toList))
  }

  /**
   * Enables all registered channels and starts delivery of pending messages.
   */
  def deliver()(implicit timeout: Timeout): Future[Any] = {
    journal ? BatchDeliverOutMsgs(channels.values.toList)
  }

  /**
   * Enables the channel registered under `channelId` and starts delivery of pending messages.
   */
  def deliver(channelId: Int)(implicit timeout: Timeout): Future[Any] = {
    journal ? BatchDeliverOutMsgs(channels.get(channelId).toList)
  }

  /**
   * Enables the specified channels and starts delivery of pending messages.
   */
  def deliver(channels: Seq[ActorRef])(implicit timeout: Timeout): Future[Any] = {
    journal ? BatchDeliverOutMsgs(channels)
  }

  /**
   * Recovers all processors and channels registered at this extension by
   * sequentially executing `replay(_ => Some(0))` and then `deliver()`.
   * Replay is done with no lower bound (i.e. with all messages in the journal).
   *
   * This method waits for replayed messages being added to processor mailboxes but
   * does not wait for replayed input messages being processed. However, any new message
   * sent to any of the selected processors, after this method successfully returned,
   * will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @param waitAtMost  wait for the specified duration for the replay to complete.
   * @throws TimeoutException if replay doesn't complete within the specified duration.
   */
  def recover(waitAtMost: FiniteDuration = 1 minute) {
    recover(_ => Some(0), waitAtMost)
  }

  /**
   * Recovers selected processors and all channels registered at this extension by
   * sequentially executing `replay(f)` and then `deliver()`.
   *
   * This method waits for replayed messages being added to processor mailboxes but
   * does not wait for replayed input messages being processed. However, any new message
   * sent to any of the selected processors, after this method successfully returned,
   * will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay will be done for that processor. If it returns `Some(snr)`
   *        message replay will start from sequence number `snr` for that processor.
   * @param waitAtMost  wait for the specified duration for the replay to complete.
   * @throws TimeoutException if replay doesn't complete within the specified duration.
   */
  def recover(f: (Int) => Option[Long], waitAtMost: FiniteDuration) {
    implicit val timeout = Timeout(waitAtMost)
    import system.dispatcher
    val c = for {
      _ <- replay(f)
      _ <- deliver()
    } yield ()

    Try(Await.result(c, waitAtMost)) match {
      case Success(_) => ()
      case Failure(e: TimeoutException) => throw new TimeoutException("recovery could not be completed within %s" format waitAtMost)
      case Failure(e) =>                   throw e
    }
  }

  /**
   * Returns a `Future` that will be completed when selected processors have finished
   * processing all pending messages in their mailboxes.
   *
   * @param processorIds ids of registered processors to wait for. Default value
   *        is the set of all registered processor ids.
   */
  def completeProcessing(processorIds: Set[Int] = processors.keySet)(implicit timeout: Timeout): Future[Any] = {
    import Eventsourced._
    import system.dispatcher

    val selected = processors.filter { case (k, _) => processorIds.contains(k) }.values
    Future.sequence(selected.map(p => p.ask(CompleteProcessing))).mapTo[Any]
  }

  /**
   * Waits for selected processors to finish processing of all pending messages
   * in their mailboxes.
   *
   * @param processorIds ids of registered processors to wait for. Default value
   *        is the set of all registered processor ids.
   * @param atMost maximum duration to wait for processing to complete.
   */
  def awaitProcessing(processorIds: Set[Int] = processors.keySet, atMost: FiniteDuration = 1 minute) {
    Await.result(completeProcessing(processorIds)(Timeout(atMost)), atMost)
  }

  @tailrec
  private def registerChannel(channelId: Int, channelName: Option[String], channel: ActorRef) {
    val current = channelsRef.get()
    val updated = if (channelName.isDefined) current.add(channelId, channelName.get, channel) else current.add(channelId, channel)
    if (!channelsRef.compareAndSet(current, updated)) registerChannel(channelId, channelName, channel)
  }

  @tailrec
  private def registerProcessor(processorId: Int, processor: ActorRef) {
    val current = processorsRef.get()
    val updated = current + (processorId -> processor)
    if (!processorsRef.compareAndSet(current, updated)) registerProcessor(processorId, processor)
  }

  @tailrec
  private [core] final def deregisterChannel(channelId: Int) {
    val current = channelsRef.get()
    val updated = current.remove(channelId)
    if (!channelsRef.compareAndSet(current, updated)) deregisterChannel(channelId)
  }

  @tailrec
  private [core] final def deregisterProcessor(processorId: Int) {
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
