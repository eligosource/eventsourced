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
 * Stackable modification for making an actor persitent via event-sourcing (or command-sourcing).
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
 *  val myActor = extension.processorOf(ProcessorProps(1, new MyActor with Eventsourced))
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
 * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
 */
trait Eventsourced extends TargetBehavior {
  import Eventsourced._

  private val journal = EventsourcingExtension(context.system).journal
  private var _id: Int = _

  /**
   * Overrides to `false` to avoid that an existing [[org.eligosource.eventsourced.core.Receiver]]
   * modification additionally sends an `Ack` to the current `sender`. This ensures that the current
   * sender will receive `Ack`s only from the journal that is used by this trait.
   */
  override val autoAck = false

  /**
   * Processor id. Must only be accessed from within the modified actor's
   * `receive` method.
   */
  def id: Int = _id

  abstract override def receive = {
    case SetId(id) => {
      _id = id
    }
    case msg: Message if (sender == journal) => {
      super.receive(msg.copy(processorId = id))
    }
    case msg: Message => {
      journal forward WriteInMsg(id, msg, self)
    }
    case LoopThrough(msg, _) => {
      super.receive(msg)
    }
    case msg => {
      // won't be written to journal but must be looped through
      // journal actor in order to to preserve order of event
      // messages and non-event messages sent to this actor
      journal forward LoopThrough(msg, self)
    }
  }
}

private [core] object Eventsourced {
  case class SetId(id: Int)
}