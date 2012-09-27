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

/**
 * A context for [[org.eligosource.eventsourced.core.Eventsourced]] processors and
 * [[org.eligosource.eventsourced.core.Channel]]s that are used by processors to emit
 * event [[org.eligosource.eventsourced.core.Message]]s to destinations. A Destination
 * can be any actor including a processor of this context.
 *
 * @param journal Journal for this context. A journal must not be shared between
 *        contexts.
 * @param processors Actors modified with the [[org.eligosource.eventsourced.core.Eventsourced]]
 *        trait.
 * @param channels [[org.eligosource.eventsourced.core.Channel]] actor map used by
 *        processors to emit event messages. Keys of the `channels` map are application-
 *        defined channel names.
 */
case class Context(
    journal: ActorRef,
    processors: Map[Int, ActorRef],
    channels: Map[String, ActorRef],
    producer: ActorRef)(implicit system: ActorSystem) {

  /**
   * Adds an event processor and returns an updated context.
   *
   * @param id event processor id.
   * @param processor event processor factory.
   * @return updated context.
   */
  def addProcessor(id: Int, processor: => Eventsourced): Context = {
    addProcessor(id, system.actorOf(Props(processor)))
  }

  /**
   * Adds an event processor and returns an updated context.
   *
   * @param id event processor id.
   * @param processor event processor.
   * @return updated context.
   */
  def addProcessor(id: Int, processor: ActorRef): Context = {
    if (id < 1) throw new IllegalArgumentException("processor id must be > 0")
    copy(processors = processors + (id -> processor))
  }

  /**
   * Creates and adds a named [[org.eligosource.eventsourced.core.DefaultChannel]]
   * and returns an updated context. Channel destination is a processor of this
   * context, referenced by `processorId`.
   *
   * @param name channel name.
   * @param processorId destination processor id.
   * @return updated context.
   */
  def addChannel(name: String, processorId: Int): Context = {
    processors.get(processorId).map(p => addChannel(name, p, None)).getOrElse(this)
  }

  /**
   * Creates and adds a named [[org.eligosource.eventsourced.core.DefaultChannel]]
   * and returns an updated context.
   *
   * @param name channel name.
   * @param destination channel destination factory.
   * @return updated context.
   */
  def addChannel(name: String, destination: => Receiver): Context = {
    addChannel(name, system.actorOf(Props(destination)), None)
  }

  /**
   * Creates and adds a named [[org.eligosource.eventsourced.core.DefaultChannel]]
   * and returns an updated context.
   *
   * @param name channel name.
   * @param destination channel destination which can be any actor including processors of
   *        this context.
   * @param replyDestination optional reply destination where responses from the destination
   *        are routed to.
   * @return updated context.
   */
  def addChannel(name: String, destination: ActorRef, replyDestination: Option[ActorRef] = None): Context = {
    val channelId = channels.size + 1
    val channel = system.actorOf(Props(new DefaultChannel(channelId, journal)))

    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))

    copy(channels = channels + (name -> channel))
  }

  /**
   * Creates and adds a named [[org.eligosource.eventsourced.core.ReliableChannel]]
   * and returns an updated context. Channel destination is a processor of this
   * context, referenced by `processorId`.
   *
   * @param name channel name.
   * @param processorId destination processor id.
   * @return updated context.
   */
  def addReliableChannel(name: String, processorId: Int): Context = {
    processors.get(processorId).map(p => addReliableChannel(name, p, None)).getOrElse(this)
  }

  /**
   * Creates and adds a named [[org.eligosource.eventsourced.core.ReliableChannel]]
   * and returns an updated context.
   *
   * @param name channel name.
   * @param destination channel destination factory.
   * @return updated context.
   */
  def addReliableChannel(name: String, destination: => Receiver): Context = {
    addReliableChannel(name, system.actorOf(Props(destination)), None)
  }

  /**
   * Creates and adds a named [[org.eligosource.eventsourced.core.ReliableChannel]]
   * and returns an updated context.
   *
   * @param name channel name.
   * @param destination channel destination which can be any actor including processors
   *        of this context.
   * @param replyDestination optional reply destination where responses from the destination
   *        are routed to.
   * @return updated context.
   */
  def addReliableChannel(name: String, destination: ActorRef, replyDestination: Option[ActorRef] = None, conf: ReliableChannelConf = ReliableChannelConf()): Context = {
    val channelId = channels.size + 1
    val channel = system.actorOf(Props(new ReliableChannel(channelId, journal, conf)))

    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))

    copy(channels = channels + (name -> channel))
  }

  /**
   * Injects this context and their ids into processors.
   *
   * @return this context.
   */
  def inject(): Context = {
    processors.foreach { kv =>
      val (id, processor) = kv
      processor ! SetId(id)
      processor ! SetContext(this)
    }
    this
  }

  /**
   * Replays input messages to processors.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay is done for that processor. If it returns `Some(snr)`
   *        message replay starts from sequence number `snr` for that processor.
   * @return this context.
   */
  def replay(f: (Int) => Option[Long]): Context = {
    val replays = processors.collect { case kv if (f(kv._1).isDefined) => ReplayInMsgs(kv._1, f(kv._1).get, kv._2) }
    journal ! BatchReplayInMsgs(replays.toList)
    this
  }

  /**
   * Enables all output channels of this context and starts delivery of pending messages.
   *
   * @return this context.
   */
  def deliver(): Context = {
    journal ! BatchDeliverOutMsgs(channels.values.toList)
    this
  }

  /**
   * Initializes this context by calling `inject()`, `replay()` and `deliver()`
   * in that order. Replay is done with no lower bound (i.e. with all messages
   * in the journal) for all processors of this context. When this method returns
   * the processors of this context are ready to receive event messages.
   *
   * @return this context.
   */
  def init(): Context = {
    init(_ => Some(0))
  }

  /**
   * Initializes this context by calling `inject()`, `replay()` and `deliver()`
   * in that order. Replay is done with lower bounds given by function `f`. When
   * this method returns the processors of this context are ready to receive event
   * messages.
   *
   * @param f function called for each processor id. If the function returns `None`
   *        no message replay is done for that processor. If it returns `Some(snr)`
   *        message replay starts from sequence number `snr` for that processor.
   * @return this context.
   */
  def init(f: (Int) => Option[Long]): Context = {
    inject()
    replay(f)
    deliver()
  }
}

object Context {
  /**
   * Creates a new context with no processors and an empty channel map.
   *
   * @param journal journal for the new context.
   * @return new context.
   */
  def apply(journal: ActorRef)(implicit system: ActorSystem) =
    new Context(journal, Map.empty, Map.empty, system.actorOf(Props[Producer]))
}