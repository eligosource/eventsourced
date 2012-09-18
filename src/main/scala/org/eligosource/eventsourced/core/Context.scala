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

case class Context(
    journal: ActorRef,
    processors: Map[Int, ActorRef],
    channels: Map[String, ActorRef],
    producer: ActorRef)(implicit system: ActorSystem) {

  def addProcessor(id: Int, processor: => Eventsourced): Context = {
    addProcessor(id, system.actorOf(Props(processor)))
  }

  def addProcessor(id: Int, processor: ActorRef): Context = {
    if (id < 1) throw new IllegalArgumentException("processor id must be > 0")
    copy(processors = processors + (id -> processor))
  }

  def addChannel(name: String, processorId: Int): Context = {
    processors.get(processorId).map(p => addChannel(name, p, None)).getOrElse(this)
  }

  def addChannel(name: String, destination: => Receiver): Context = {
    addChannel(name, system.actorOf(Props(destination)), None)
  }

  def addChannel(name: String, destination: ActorRef, replyDestination: Option[ActorRef] = None): Context = {
    val channelId = channels.size + 1
    val channel = system.actorOf(Props(new DefaultChannel(channelId, journal)))

    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))

    copy(channels = channels + (name -> channel))
  }

  def addReliableChannel(name: String, processorId: Int): Context = {
    processors.get(processorId).map(p => addReliableChannel(name, p, None)).getOrElse(this)
  }

  def addReliableChannel(name: String, destination: => Receiver): Context = {
    addReliableChannel(name, system.actorOf(Props(destination)), None)
  }

  def addReliableChannel(name: String, destination: ActorRef, replyDestination: Option[ActorRef] = None, conf: ReliableChannelConf = ReliableChannelConf()): Context = {
    val channelId = channels.size + 1
    val channel = system.actorOf(Props(new ReliableChannel(channelId, journal, conf)))

    channel ! Channel.SetDestination(destination)
    replyDestination.foreach(rd => channel ! Channel.SetReplyDestination(rd))

    copy(channels = channels + (name -> channel))
  }

  def inject(): Context = {
    processors.foreach { kv =>
      val (id, processor) = kv
      processor ! SetId(id)
      processor ! SetContext(this)
    }
    this
  }

  def replay(f: (Int) => Option[Long]): Context = {
    val replays = processors.collect { case kv if (f(kv._1).isDefined) => ReplayInMsgs(kv._1, f(kv._1).get, kv._2) }
    journal ! BatchReplayInMsgs(replays.toList)
    this
  }

  def deliver(): Context = {
    journal ! BatchDeliverOutMsgs(channels.values.toList)
    this
  }

  /**
   * Initilizes this context by injecting this context into processors,
   * replaying journaled events to processors and initializing channels.
   * Replay is done for all processors with no lower bound on sequence
   * numbers.
   *
   * @return this context.
   */
  def init(): Context = {
    init(_ => Some(0))
  }

  /**
   * Initilizes this context by injecting this context into processors,
   * replaying journaled events to processors and initializing channels.
   *
   * @param f called by the initialization procodure to determine to which
   *          processors event messages should be replayed and from which
   *          sequence number.
   * @return this context.
   */
  def init(f: (Int) => Option[Long]): Context = {
    inject()
    replay(f)
    deliver()
  }
}

object Context {
  def apply(journal: ActorRef)(implicit system: ActorSystem) =
    new Context(journal, Map.empty, Map.empty, system.actorOf(Props[Producer]))
}