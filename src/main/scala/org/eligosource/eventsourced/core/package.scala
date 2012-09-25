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
package org.eligosource.eventsourced

import akka.actor.ActorRef

package object core {
  private [core] case class SetId(id: Int)
  private [core] case class SetContext(context: Context)

  case object Ack

  val SkipAck: Long = -1L

  implicit def processorRef2Initiator(processor: ActorRef) = {
    new Initiator(processor)
  }

  // ------------------------------------------------------------
  //  Factories for special-purpose processors
  // ------------------------------------------------------------

  def decorator(target: ActorRef): Eventsourced =
    new Decorator(target) with Eventsourced

  def multicast(targets: Seq[ActorRef]): Eventsourced =
    new Multicast(targets) with Eventsourced with ForwardContext with ForwardMessage
}