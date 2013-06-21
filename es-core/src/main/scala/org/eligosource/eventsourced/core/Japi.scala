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

/**
 * Java API.
 *
 * Do not extend this class directly. It is identical to [[akka.actor.UntypedActor]]
 * except that `receive` is non-final which allows for decoration with stackable traits.
 */
abstract class UntypedActorSupport extends Actor {

  // ----------------------------------------------------------
  //  Temporary copy-paste of akka.actor.UntypedActor because UntypedActor.receive is
  //  final and cannot be decorated with stackable traits.
  // ----------------------------------------------------------

  override def supervisorStrategy: SupervisorStrategy = super.supervisorStrategy

  def getContext(): UntypedActorContext = context.asInstanceOf[UntypedActorContext]
  def getSelf(): ActorRef = self
  def getSender(): ActorRef = sender

  @throws(classOf[Exception]) def onReceive(message: Any): Unit

  @throws(classOf[Exception]) override def preStart(): Unit = super.preStart()
  @throws(classOf[Exception]) override def postStop(): Unit = super.postStop()

  @throws(classOf[Exception]) override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    super.preRestart(reason, message)
  @throws(classOf[Exception]) override def postRestart(reason: Throwable): Unit =
    super.postRestart(reason)

  @throws(classOf[Exception]) def receive = { case msg => onReceive(msg) }
}

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Receiver]].
 */
abstract class UntypedReceiver extends UntypedActorSupport with Receiver

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Emitter]].
 */
abstract class UntypedEmitter extends UntypedActorSupport with Emitter

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Confirm]].
 */
abstract class UntypedConfirmingActor extends UntypedActorSupport with Confirm

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Eventsourced]].
 */
abstract class UntypedEventsourcedActor extends UntypedActorSupport with Eventsourced

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Receiver]] and
 * [[org.eligosource.eventsourced.core.Confirm]]Base class for untyped actors, modified with .
 */
abstract class UntypedConfirmingReceiver extends UntypedReceiver with Confirm

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Receiver]] and
 * [[org.eligosource.eventsourced.core.Eventsourced]].
 */
abstract class UntypedEventsourcedReceiver extends UntypedReceiver with Eventsourced

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Emitter]] and
 * [[org.eligosource.eventsourced.core.Eventsourced]].
 */
abstract class UntypedEventsourcedEmitter extends UntypedEmitter with Eventsourced

/**
 * Java API.
 *
 * Base class for untyped actors, modified with [[org.eligosource.eventsourced.core.Receiver]],
 * [[org.eligosource.eventsourced.core.Confirm]] and
 * [[org.eligosource.eventsourced.core.Eventsourced]].
 */
abstract class UntypedEventsourcedConfirmingReceiver extends UntypedConfirmingReceiver with Eventsourced
