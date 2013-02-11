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
 * Stackable modification for actors that want to automatically confirm the
 * receipt of an event [[org.eligosource.eventsourced.core.Message]] from a
 * [[org.eligosource.eventsourced.core.Channel]]. If the modified actor's
 * `receive` method successfully returns, this trait calls `confirm(true)` on
 * the received event message. If `receive` throws an exception, this trait
 * calls `confirm(false)` on the received event message and re-throws the
 * exception. Usage example:
 *
 * {{{
 *   val myActor = system.actorOf(Props(new MyActor with Confirm))
 *
 *   class MyActor extends Actor {
 *     def receive = {
 *       case msg: Message => // ...
 *     }
 *   }
 * }}}
 *
 * This trait can also be used in combination with other stackable traits of the
 * library (such as [[org.eligosource.eventsourced.core.Receiver]],
 * [[org.eligosource.eventsourced.core.Emitter]] or
 * [[org.eligosource.eventsourced.core.Eventsourced]]), for example:
 *
 * {{{
 *   val myActor = system.actorOf(Props(new MyActor with Receiver with Confirm with Eventsourced { val id = ... } ))
 *
 *   class MyActor extends Actor {
 *     def receive = {
 *       case event => // ...
 *     }
 *   }
 * }}}
 */
trait Confirm extends Actor {
  abstract override def receive = {
    case msg: Message => {
      try {
        super.receive(msg)
        msg.confirm(true)
      } catch {
        case e: Throwable => { msg.confirm(false); throw e }
      }
    }
    case msg => {
      super.receive(msg)
    }
  }
}
