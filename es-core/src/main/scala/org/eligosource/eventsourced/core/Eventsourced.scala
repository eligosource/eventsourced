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

import org.eligosource.eventsourced.core.JournalProtocol._

/**
 * Stackable modification for making an actor persistent via event-sourcing (or command-sourcing).
 * It writes any input [[org.eligosource.eventsourced.core.Message]] to a journal. Input messages
 * of any other type are not journaled. Example:
 *
 * {{{
 *  val system: ActorSystem = ...
 *  val journal: ActorRef = ...
 *  val extension = EventsourcingExtension(system, journal)
 *
 *  class MyActor extends Actor {
 *    def receive = {
 *      case msg: Message => // journaled event message
 *      case msg          => // non-journaled message
 *    }
 *  }
 *
 *  // create and register and event-sourced actor (processor)
 *  val myActor = extension.processorOf(Props(new MyActor with Eventsourced { val id = 1 } ))
 *
 *  // replay journaled messages from previous application runs
 *  extension.recover()
 *
 *  myActor ! Message("foo event") // message will be journaled
 *  myActor ! "whatever"           // message will not be journaled
 * }}}
 *
 * If the `Eventsourced` trait is used in combination with [[org.eligosource.eventsourced.core.Receiver]]
 * or [[org.eligosource.eventsourced.core.Emitter]], `Eventsourced` must be the last modification:
 *
 * {{{
 *  new Actor with Receiver with Eventsourced { ... }  // ok
 *  new Actor with Emitter with Eventsourced { ... }   // ok
 *  new Actor with Eventsourced with Receiver { ... }  // won't work
 *  new Actor with Eventsourced with Emitter { ... }   // won't work
 * }}}
 *
 * The `Eventsourced` trait can additionally be combined with the stackable
 * [[org.eligosource.eventsourced.core.Confirm]] trait.
 *
 * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 */
trait Eventsourced extends Behavior {
  import Eventsourced._

  protected val extension = EventsourcingExtension(context.system)
  protected val journal = extension.journal

  /**
   * Processor id.
   */
  def id: Int

  abstract override def receive = {
    case GetId => {
      sender ! id
    }
    case msg: Message => {
      journal forward WriteInMsg(id, msg.copy(processorId = id), self)
    }
    case Written(msg) => {
      // optionally set processorId for backwards compatibility with existing stores
      super.receive(if (msg.processorId == 0L) msg.copy(processorId = id) else msg)
    }
    case Looped(CompleteProcessing) => {
      sender ! Completed
    }
    case Looped(msg) => {
      super.receive(msg)
    }
    case SnapshotRequest => {
      journal forward RequestSnapshot(id, self)
    }
    case sr: SnapshotRequest => {
      super.receive(sr)
    }
    case so: SnapshotOffer => {
      super.receive(so)
    }
    case msg => {
      // won't be written to journal but must be looped through
      // journal actor in order to to preserve order of event
      // messages and non-event messages sent to this actor
      journal forward Loop(msg, self)
    }
  }

  /**
   * Calls `super.postStop` and then de-registers this processor from
   * [[org.eligosource.eventsourced.core.EventsourcingExtension]].
   */
  abstract override def postStop() {
    super.postStop()
    extension.deregisterProcessor(id)
  }
}

private [eventsourced] object Eventsourced {
  case object GetId

  case object CompleteProcessing
  case object Completed
}