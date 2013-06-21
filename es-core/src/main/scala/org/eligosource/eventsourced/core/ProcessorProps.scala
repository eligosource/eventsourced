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

import akka.actor._
import akka.japi.{Function => JFunction}

/**
 * [[org.eligosource.eventsourced.core.Eventsourced]] processor configuration object.
 *
 * @param id Processor id.
 * @param processorFactory Processor factory.
 * @param name Optional processor name.
 * @param dispatcherName Optional dispatcher name.
 */
case class ProcessorProps(
   id: Int,
   processorFactory: Int => Actor with Eventsourced,
   name: Option[String] = None,
   dispatcherName: Option[String] = None) {

  /**
   * Returns a new `ProcessorProps` with the specified name.
   */
  def withName(name: String) =
    copy(name = Some(name))

  /**
   * Returns a new `ProcessorProps` with the specified dispatcher name.
   */
  def withDispatcherName(dispatcherName: String) =
    copy(dispatcherName = Some(dispatcherName))

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
    var props = Props(processorFactory(id))

    if (dispatcherName.isDefined)
      props = props.withDispatcher(dispatcherName.get)

    if (name.isDefined)
      actorRefFactory.actorOf(props, name.get) else
      actorRefFactory.actorOf(props)
  }
}

object ProcessorProps {
  /**
   * Java API.
   *
   * Creates a new `ProcessorProps` object with specified `id` and `processorFactory`.
   *
   * @param id processor id.
   * @param processorFactory processor factory.
   */
  def create(id: Int, processorFactory: JFunction[Integer, Actor with Eventsourced]) =
    new ProcessorProps(id, pid => processorFactory(pid))
}