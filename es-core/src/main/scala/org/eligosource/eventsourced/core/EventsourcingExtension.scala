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

import java.lang.{Boolean => JBoolean}
import java.util.concurrent.atomic.AtomicReference
import java.util.{List => JList, Set => JSet}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

import akka.actor._
import akka.japi.{Function => JFunction}
import akka.pattern._
import akka.util.Timeout

import org.eligosource.eventsourced.core.JournalProtocol._

/**
 * Event-sourcing extension for Akka. Used by applications to create and register
 * [[org.eligosource.eventsourced.core.Eventsourced]] processors and event message
 * [[org.eligosource.eventsourced.core.Channel]]s and to recover them from journaled
 * event [[org.eligosource.eventsourced.core.Message]]s and, optionally, snapshots.
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
   * Java API.
   *
   * Map of registered [[org.eligosource.eventsourced.core.Channel]]s. Mapping key is
   * the channel id.
   */
  def getChannels: java.util.Map[Integer, ActorRef] =
    channels map { case (k, v) => new Integer(k) -> v } asJava

  /**
   * Map of registered named [[org.eligosource.eventsourced.core.Channel]]s. Mapping key is
   * the channel name.
   */
  def namedChannels: Map[String, ActorRef] =
    channelsRef.get.nameToChannel

  /**
   * Java API.
   *
   * Map of registered named [[org.eligosource.eventsourced.core.Channel]]s. Mapping key is
   * the channel name.
   */
  def getNamedChannels: java.util.Map[String, ActorRef] =
    namedChannels.asJava

  /**
   * Map of registered [[org.eligosource.eventsourced.core.Eventsourced]] processors.
   * Mapping key is the processor id.
   */
  def processors: Map[Int, ActorRef] =
    processorsRef.get

  /**
   * Java API.
   *
   * Map of registered [[org.eligosource.eventsourced.core.Eventsourced]] processors.
   * Mapping key is the processor id.
   */
  def getProcessors: java.util.Map[Integer, ActorRef] =
    processors map { case (k, v) => new Integer(k) -> v } asJava

  /**
   * Registers an [[org.eligosource.eventsourced.core.Eventsourced]] processor.
   *
   * @param props processor configuration object.
   * @return a processor ref.
   * @throws InvalidActorNameException if `props.name` is defined and already
   *         in use in the underlying actor system.
   * @throws InvalidProcessorIdException if processor id < 1.
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
   * @param name optional processor name. If `None`, a name is generated.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Eventsourced]]
   *        ref factory.
   * @return a processor ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   * @throws InvalidProcessorIdException if processor id < 1.
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
   * Java API.
   *
   * Registers an [[org.eligosource.eventsourced.core.Eventsourced]] processor with
   * a generated actor name.
   *
   * This method obtains the id from the created processor with a blocking operation.
   * Use the overloaded `processorOf(ProcessorProps)` method if you want to avoid
   * blocking.
   *
   * @param props actor ref configuration object.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Eventsourced]]
   *        ref factory.
   * @return a processor ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   * @throws InvalidProcessorIdException if processor id < 1.
   */
  def processorOf(props: Props, actorRefFactory: ActorRefFactory): ActorRef =
    processorOf(props, None)(actorRefFactory)

  /**
   * Java API.
   *
   * Registers an [[org.eligosource.eventsourced.core.Eventsourced]] processor with a
   * specified actor name.
   *
   * This method obtains the id from the created processor with a blocking operation.
   * Use the overloaded `processorOf(ProcessorProps)` method if you want to avoid
   * blocking.
   *
   * @param props actor ref configuration object.
   * @param name processor name.
   * @param actorRefFactory [[org.eligosource.eventsourced.core.Eventsourced]]
   *        ref factory.
   * @return a processor ref.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   * @throws InvalidProcessorIdException if processor id < 1.
   */
  def processorOf(props: Props, name: String, actorRefFactory: ActorRefFactory): ActorRef =
    processorOf(props, Some(name))(actorRefFactory)

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
   * @throws InvalidChannelIdException if channel id < 1.
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
   * Predefined `ReplayParams` sequences to be used with methods `replay` or `recover`.
   *
   * @see [[org.eligosource.eventsourced.core.ReplayParams]]
   */
  object replayParams {
    /**
     * Replay parameters for all registered processors for non-snapshotted replay,
     * starting from sequence number 0 (= from scratch) and no upper sequence number
     * bound.
     */
    def allFromScratch: Seq[ReplayParams] =
      processors.keys.map(pid => ReplayParams(pid)).toSeq

    /**
     * Replay parameters for all registered processors for snapshotted replay,
     * starting from the latest available snapshot and no upper sequence number
     * bound.
     */
    def allWithSnapshot: Seq[ReplayParams] =
      processors.keys.map(pid => ReplayParams(pid, true)).toSeq

    /**
     * Replay parameters for all registered processors for snapshotted replay,
     * starting from the latest filtered snapshot and no upper sequence number
     * bound.
     */
    def allWithSnapshot(snapshotFilter: SnapshotMetadata => Boolean): Seq[ReplayParams] =
      processors.keys.map(pid => ReplayParams(pid, snapshotFilter)).toSeq

    /**
     * Java API.
     *
     * Replay parameters for all registered processors for snapshotted replay,
     * starting from the latest filtered snapshot and no upper sequence number
     * bound.
     */
    def allWithSnapshot(snapshotFilter: JFunction[SnapshotMetadata, JBoolean]): Seq[ReplayParams] =
      allWithSnapshot(smd => snapshotFilter(smd))

    /**
     * Replay parameters for selected registered processors for non-snapshotted replay,
     * with a user-defined lower sequence number bound and no upper sequence number
     * bound.
     *
     * @param f called with the id of each registered processor. If `None` is returned,
     *        no replay will be done for the corresponding processor, otherwise replay
     *        will be started from the returned sequence number (with no upper bound).
     */
    private [eventsourced] def selectedWith(f: (Int) => Option[Long]): Seq[ReplayParams] = processors.collect {
      case (pid, p) if (f(pid).isDefined) => ReplayParams(pid, f(pid).get)
    } toList
  }

  /**
   * Java API.
   *
   * Predefined `ReplayParams` sequences to be used with methods `replay` or `recover`.
   *
   * @see [[org.eligosource.eventsourced.core.ReplayParams]]
   */
  def getReplayParams = replayParams

  /**
   * Replays input messages to specified processors, optionally based on a snapshot.
   * The returned `Future` will be completed when the replayed messages have been
   * sent (via `!`) to the specified processors. Any new message sent to any of the
   * specified processors, after successful completion of the returned `Future`, will
   * be processed after the replayed input messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after the returned `Future` successfully completed.
   *
   * @param params sequence of processor-specific replay parameters.
   * @see [[org.eligosource.eventsourced.core.ReplayParams]]
   */
  def replay(params: Seq[ReplayParams])(implicit timeout: Timeout): Future[Any] = {
    val replays = params.foldLeft(List.empty[ReplayInMsgs]) {
      case (acc, params) => processors.get(params.processorId) match {
        case Some(p) => ReplayInMsgs(params, p) :: acc
        case None    => acc
      }
    }
    journal ? (BatchReplayInMsgs(replays))
  }

  /**
   * Java API.
   *
   * Replays input messages to specified processors, optionally based on a snapshot.
   * The returned `Future` will be completed when the replayed messages have been
   * sent (via `!`) to the specified processors. Any new message sent to any of the
   * specified processors, after successful completion of the returned `Future`, will
   * be processed after the replayed input messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after the returned `Future` successfully completed.
   *
   * @param params sequence of processor-specific replay parameters.
   * @see [[org.eligosource.eventsourced.core.ReplayParams]]
   */
  def replay(params: JList[ReplayParams], timeout: Timeout): Future[Any] = {
    replay(params.asScala)(timeout)
  }

  @deprecated("use replay(Seq[ReplayParams])(Timeout)", "0.5")
  def replay(f: (Int) => Option[Long])(implicit timeout: Timeout): Future[Any] = {
    replay(replayParams.selectedWith(f))
  }

  /**
   * Activates all registered channels and starts delivery of pending messages.
   */
  def deliver()(implicit timeout: Timeout): Future[Any] = {
    journal ? BatchDeliverOutMsgs(channels.values.toList)
  }

  /**
   * Activates the channel registered under `channelId` and starts delivery of pending messages.
   */
  def deliver(channelId: Int)(implicit timeout: Timeout): Future[Any] = {
    journal ? BatchDeliverOutMsgs(channels.get(channelId).toList)
  }

  /**
   * Activates the specified channels and starts delivery of pending messages.
   */
  def deliver(channels: Seq[ActorRef])(implicit timeout: Timeout): Future[Any] = {
    journal ? BatchDeliverOutMsgs(channels)
  }

  /**
   * Java API.
   *
   * Activates the specified channels and starts delivery of pending messages.
   */
  def deliver(channels: JList[ActorRef], timeout: Timeout): Future[Any] = {
    deliver(channels.asScala)(timeout)
  }

  /**
   * Recovers all processors and all channels registered at this extension where
   *
   *  - processor recovery is done by calling `replay(params)`
   *  - channel activation is done by calling `deliver()`
   *
   * Channel activation is only started after processor recovery successfully completed.
   *
   * This method waits for replayed messages being sent to all processors (via `!`)
   * and all channels being activated but does not wait for replayed input messages being
   * processed. However, any new message sent to any of the processors, after this method
   * successfully returned, will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @param waitAtMost wait for the specified duration for the replay to complete.
   * @throws TimeoutException if replay doesn't complete within the specified duration.
   */
  def recover(waitAtMost: FiniteDuration) {
    recover(replayParams.allFromScratch, waitAtMost)
  }

  /**
   * Recovers all processors and all channels registered at this extension where
   *
   *  - processor recovery is done by calling `replay(params)`
   *  - channel activation is done by calling `deliver()`
   *
   * Channel activation is only started after processor recovery successfully completed.
   *
   * This method waits for replayed messages being sent to all processors (via `!`)
   * and all channels being activated but does not wait for replayed input messages being
   * processed. However, any new message sent to any of the processors, after this method
   * successfully returned, will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @throws TimeoutException if replay doesn't complete within the specified duration (which
   *         defaults to 1 minute).
   */
  def recover() {
    recover(1 minute)
  }

  /**
   * Recovers specified processors and all channels registered at this extension where
   *
   *  - processor recovery is done by calling `replay(params)`
   *  - channel activation is done by calling `deliver()`
   *
   * Channel activation is only started after processor recovery successfully completed.
   *
   * This method waits for replayed messages being sent to specified processors (via `!`)
   * and all channels being activated but does not wait for replayed input messages being
   * processed. However, any new message sent to any of the specified processors, after
   * this method successfully returned, will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @param params replay parameters passed to `replay(params)`.
   * @throws TimeoutException if replay doesn't complete within 1 minute.
   */
  def recover(params: Seq[ReplayParams]) {
    recover(params, 1 minute)
  }

  /**
   * Recovers specified processors and all channels registered at this extension where
   *
   *  - processor recovery is done by calling `replay(params)`
   *  - channel activation is done by calling `deliver()`
   *
   * Channel activation is only started after processor recovery successfully completed.
   *
   * This method waits for replayed messages being sent to specified processors (via `!`)
   * and all channels being activated but does not wait for replayed input messages being
   * processed. However, any new message sent to any of the specified processors, after
   * this method successfully returned, will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @param params replay parameters passed to `replay(params)`.
   * @param waitAtMost wait for the specified duration for the replay to complete.
   * @throws TimeoutException if replay doesn't complete within the specified duration.
   */
  def recover(params: Seq[ReplayParams], waitAtMost: FiniteDuration) {
    implicit val timeout = Timeout(waitAtMost)
    import system.dispatcher
    val c = for {
      _ <- replay(params)
      _ <- deliver()
    } yield ()

    Try(Await.result(c, waitAtMost)) match {
      case Success(_) => ()
      case Failure(e: TimeoutException) => throw new TimeoutException("recovery could not be completed within %s" format waitAtMost)
      case Failure(e) =>                   throw e
    }
  }

  /**
   * Java API.
   *
   * Recovers specified processors and all channels registered at this extension where
   *
   *  - processor recovery is done by calling `replay(params)`
   *  - channel activation is done by calling `deliver()`
   *
   * Channel activation is only started after processor recovery successfully completed.
   *
   * This method waits for replayed messages being sent to specified processors (via `!`)
   * and all channels being activated but does not wait for replayed input messages being
   * processed. However, any new message sent to any of the specified processors, after
   * this method successfully returned, will be processed after the replayed event messages.
   *
   * Clients that want to wait for replayed messages being processed should call the
   * `awaitProcessing` method after this method successfully returned.
   *
   * @param params replay parameters passed to `replay(params)`.
   * @param waitAtMost wait for the specified duration for the replay to complete.
   * @throws TimeoutException if replay doesn't complete within the specified duration.
   */
  def recover(params: JList[ReplayParams], waitAtMost: FiniteDuration) {
    recover(params.asScala, waitAtMost)
  }

  @deprecated("use recover(Seq[ReplayParams], FiniteDuration)", "0.5")
  def recover(f: (Int) => Option[Long], waitAtMost: FiniteDuration) {
    recover(replayParams.selectedWith(f), waitAtMost)
  }

  /**
   * Returns a `Future` that will be completed when specified processors have finished
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
   * Java API.
   *
   * Returns a `Future` that will be completed when specified processors have finished
   * processing all pending messages in their mailboxes.
   *
   * @param processorIds ids of registered processors to wait for. Default value
   *        is the set of all registered processor ids.
   */
  def completeProcessing(processorIds: JSet[Integer], timeout: Timeout): Future[Any] = {
    completeProcessing(processorIds.asScala.map(_.toInt).toSet)(timeout)
  }

  /**
   * Java API.
   *
   * Returns a `Future` that will be completed when all processors have finished
   * processing all pending messages in their mailboxes.
   */
  def completeProcessing(timeout: Timeout): Future[Any] = {
    completeProcessing()(timeout)
  }

  /**
   * Waits for specified processors to finish processing of all pending messages
   * in their mailboxes.
   *
   * @param processorIds ids of registered processors to wait for. Default value
   *        is the set of all registered processor ids.
   * @param atMost maximum duration to wait for processing to complete. Default
   *        value is 1 minute.
   */
  def awaitProcessing(processorIds: Set[Int] = processors.keySet, atMost: FiniteDuration = 1 minute) {
    Await.result(completeProcessing(processorIds)(Timeout(atMost)), atMost)
  }

  /**
   * Java API.
   *
   * Waits for specified processors to finish processing of all pending messages
   * in their mailboxes.
   *
   * @param processorIds ids of registered processors to wait for. Default value
   *        is the set of all registered processor ids.
   * @param atMost maximum duration to wait for processing to complete.
   */
  def awaitProcessing(processorIds: JSet[Integer], atMost: FiniteDuration) {
    awaitProcessing(processorIds.asScala.map(_.toInt).toSet, atMost)
  }

  /**
   * Java API.
   *
   * Waits for all processors to finish processing of all pending messages
   * in their mailboxes.
   *
   * @param atMost maximum duration to wait for processing to complete.
   */
  def awaitProcessing(atMost: FiniteDuration) {
    awaitProcessing(processors.keySet, atMost)
  }

  /**
   * Requests a snapshot capturing action from specified processors. These processors
   * will receive a [[org.eligosource.eventsourced.core.SnapshotRequest]] message which
   * is used to capture a snapshot via that message's `process` method. Once captured,
   * the snapshots will be saved. The future returned by this method will be completed,
   * when all snapshots have been saved.
   *
   * Calling this method for a single processor is equivalent to sending that
   * processor a [[org.eligosource.eventsourced.core.SnapshotRequest$]] message.
   *
   * @param processorIds ids of processors for which a snapshot capturing action
   *        shall be requested.
   * @param timeout snapshot capturing and saving timeout.
   */
  def snapshot(processorIds: Set[Int])(implicit timeout: Timeout): Future[Set[SnapshotSaved]] = {
    import system.dispatcher

    val selected = processors filter {
      case (k, _) => processorIds.contains(k)
    }
    val commands = selected map {
      case (k, v) => RequestSnapshot(k, v)
    }

    Future.sequence(commands.map(journal.ask(_).mapTo[SnapshotSaved]).toSet)
  }

  /**
   * Java API.
   *
   * Requests a snapshot capturing action from specified processors. These processors
   * will receive a [[org.eligosource.eventsourced.core.SnapshotRequest]] message which
   * is used to capture a snapshot via that message's `process` method. Once captured,
   * the snapshots will be saved. The future returned by this method will be completed,
   * when all snapshots have been saved.
   *
   * Calling this method for a single processor is equivalent to sending that
   * processor a `SnapshotRequest.get` message.
   *
   * @param processorIds ids of processors for which a snapshot capturing action
   *        shall be requested.
   * @param timeout snapshot capturing and saving timeout.
   */
  def snapshot(processorIds: JSet[Integer], timeout: Timeout): Future[JSet[SnapshotSaved]] = {
    snapshot(processorIds.asScala.map(_.toInt).toSet)(timeout).map(_.asJava)(system.dispatcher)
  }

  @tailrec
  private def registerChannel(channelId: Int, channelName: Option[String], channel: ActorRef)(implicit actorRefFactory: ActorRefFactory) {
    if (channelId < 1) {
      actorRefFactory.stop(channel)
      throw new InvalidChannelIdException
    }
    val current = channelsRef.get()
    val updated = if (channelName.isDefined) current.add(channelId, channelName.get, channel) else current.add(channelId, channel)
    if (!channelsRef.compareAndSet(current, updated)) registerChannel(channelId, channelName, channel)
  }

  @tailrec
  private def registerProcessor(processorId: Int, processor: ActorRef)(implicit actorRefFactory: ActorRefFactory) {
    if (processorId < 1) {
      actorRefFactory.stop(processor)
      throw new InvalidProcessorIdException
    }
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

  /**
   * Java API.
   *
   * Obtains the `EventsourcingExtension` instance associated with `system`, registers a `journal`
   * at that instance and returns it.
   *
   * @param system actor system associated with the returned extension instance.
   * @param journal journal to register.
   */
  def create(system: ActorSystem, journal: ActorRef): EventsourcingExtension =
    apply(system, journal)

  def createExtension(system: ExtendedActorSystem) =
    new EventsourcingExtension(system)

  def lookup() = EventsourcingExtension
}

/**
 * Thrown when a channel is registered with an id < 1.
 */
class InvalidChannelIdException extends RuntimeException("Channel id must be a positive integer")

/**
 * Thrown when a processor is registered with an id < 1.
 */
class InvalidProcessorIdException extends RuntimeException("Processor id must be a positive integer")