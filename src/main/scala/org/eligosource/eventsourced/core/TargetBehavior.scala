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
package akka.actor

import scala.collection.immutable.Stack

/**
 * Allows actors with a stackable [[org.eligosource.eventsourced.core.Eventsourced]]
 * and/or [[org.eligosource.eventsourced.core.Receiver]] modification (incl. sub-traits)
 * to change their behavior with `context.become()` and `context.unbecome()` without
 * loosing the functionality provided by these modifications.
 *
 * @see [[org.eligosource.eventsourced.core.Eventsourced]]
 *      [[org.eligosource.eventsourced.core.Receiver]]
 *      [[org.eligosource.eventsourced.core.Responder]]
 *      [[org.eligosource.eventsourced.core.Emitter]]
 */
trait TargetBehavior extends Actor {
  private var behaviorStack = Stack.empty[Receive].push(super.receive)

  /**
   * Controls `Ack` replies to the current `sender`.
   */
  protected def autoAck: Boolean

  abstract override def receive = {
    case msg => invoke(msg)
  }

  private def invoke(msg: Any) = {
    val head = behaviorStack.head
    if (head.isDefinedAt(msg)) head.apply(msg) else unhandled(msg)
  }

  private [akka] override def pushBehavior(behavior: Receive) {
    behaviorStack = behaviorStack.push(behavior)
  }

  private [akka] override def popBehavior() {
    val original = behaviorStack
    val popped = original.pop
    behaviorStack = if (popped.isEmpty) original else popped
  }

  private [akka] override def clearBehaviorStack() {
    behaviorStack = Stack.empty[Receive].push(behaviorStack.last)
  }
}
