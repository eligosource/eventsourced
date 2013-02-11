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
package org.eligosource.eventsourced

import akka.actor._

package object core {

  /**
   * Instantiates, configures and returns a actor.
   *
   * @param actor actor factory.
   * @param name optional name of the actor in the underlying actor system.
   * @param dispatcherName optional dispatcher name.
   * @throws InvalidActorNameException if `name` is defined and already in use
   *         in the underlying actor system.
   */
  def actor(actor: => Actor, name: Option[String] = None, dispatcherName: Option[String] = None)(implicit actorRefFactory: ActorRefFactory): ActorRef = {
    var props = Props(actor)

    dispatcherName.foreach { name =>
      props = props.withDispatcher(name)
    }

    if (name.isDefined)
      actorRefFactory.actorOf(props, name.get) else
      actorRefFactory.actorOf(props)
  }

  // ------------------------------------------------------------
  //  Factories for special-purpose processors
  // ------------------------------------------------------------

  /**
   * Returns a [[org.eligosource.eventsourced.core.Multicast]] processor with a
   * single `target`. Useful in situations are actors cannot be modified with
   * the stackable [[org.eligosource.eventsourced.core.Eventsourced]] trait
   * e.g. because the actor's `receive` method is declared `final`.
   *
   * @param processorId processor id.
   * @param target single multicast target.
   * @param transformer function applied to received event
   *        [[org.eligosource.eventsourced.core.Message]]s before they are forwarded
   *        to `target`.
   */
  def decorator(processorId: Int, target: ActorRef, transformer: Message => Any = identity): Actor with Eventsourced =
    multicast(processorId, List(target), transformer)

  /**
   * Returns a [[org.eligosource.eventsourced.core.Multicast]] processor.
   *
   * @param processorId processor id.
   * @param targets multicast targets.
   * @param transformer function applied to received event
   *        [[org.eligosource.eventsourced.core.Message]]s before they are forwarded
   *        to `targets`.
   */
  def multicast(processorId: Int, targets: Seq[ActorRef], transformer: Message => Any = identity): Actor with Eventsourced =
    new Multicast(targets, transformer) with Eventsourced { val id = processorId }
}