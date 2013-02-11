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

import scala.concurrent.duration.FiniteDuration

import akka.actor._

/**
 * Channel configuration object.
 */
trait ChannelProps {
  /** Channel id. */
  def id: Int
  /** Optional channel name. */
  def name: Option[String]
  /** Optional dispatcher name. */
  def dispatcherName: Option[String]
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
  name: Option[String] = None,
  dispatcherName: Option[String] = None) extends ChannelProps {

  /**
   * Returns a new `DefaultChannelProps` with the specified name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `DefaultChannelProps` with the specified dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  /**
   * Creates a [[org.eligosource.eventsourced.core.DefaultChannel]] with the
   * settings defined by this configuration object.
   */
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) =
    actor(new DefaultChannel(id, journal, destination), name, dispatcherName)
}

/**
 * [[org.eligosource.eventsourced.core.ReliableChannel]] configuration object.
 */
case class ReliableChannelProps(
  id: Int,
  destination: ActorRef,
  policy: RedeliveryPolicy = RedeliveryPolicy(),
  name: Option[String] = None,
  dispatcherName: Option[String] = None) extends ChannelProps {

  /**
   * Returns a new `ReliableChannelProps` with the specified name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `ReliableChannelProps` with the specified dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

  /**
   * Returns a new `ReliableChannelProps` with the specified confirmation timeout.
   */
  def withConfirmationTimeout(confirmationTimeout: FiniteDuration) =
    copy(policy = policy.copy(confirmationTimeout = confirmationTimeout))

  /**
   * Returns a new `ReliableChannelProps` with the specified restart delay.
   */
  def withRestartDelay(restartDelay: FiniteDuration) =
    copy(policy = policy.copy(restartDelay = restartDelay))

  /**
   * Returns a new `ReliableChannelProps` with the specified maximum number of restarts.
   */
  def withRestartMax(restartMax: Int) =
    copy(policy = policy.copy(restartMax = restartMax))

  /**
   * Returns a new `ReliableChannelProps` with the specified re-delivery delay.
   */
  def withRedeliveryDelay(redeliveryDelay: FiniteDuration) =
    copy(policy = policy.copy(redeliveryDelay = redeliveryDelay))

  /**
   * Returns a new `ReliableChannelProps` with the specified maximum number of re-delivery attempts.
   */
  def withRedeliveryMax(redeliveryMax: Int) =
    copy(policy = policy.copy(redeliveryMax = redeliveryMax))

  /**
   * Creates a [[org.eligosource.eventsourced.core.ReliableChannel]] with the
   * settings defined by this configuration object.
   */
  def createChannel(journal: ActorRef)(implicit actorRefFactory: ActorRefFactory) =
    actor(new ReliableChannel(id, journal, destination, policy, dispatcherName), name, dispatcherName)
}
