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

import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue

import akka.actor._

import org.eligosource.eventsourced.core.DefaultChannelSpec._

class DefaultChannelSpec extends EventsourcingSpec[Fixture] {
  "A default channel" must {
    "buffer messages before initial delivery" in { fixture =>
      import fixture._

      val c = channel(successDestination)

      c ! Message("a")
      c ! Message("b")
      c ! Deliver

      dequeue { m => m.event must be("a") }
      dequeue { m => m.event must be("b") }
    }
    "not buffer messages after initial delivery" in { fixture =>
      import fixture._

      val c = channel(successDestination)

      c ! message("a")
      c ! Deliver
      c ! message("b")
      c ! message("c")

      dequeue { m => m.event must be("a") }
      dequeue { m => m.event must be("b") }
      dequeue { m => m.event must be("c") }
    }
    "set posConfirmationTarget and posConfirmationMessage on delivered message" in { fixture =>
      import fixture._

      val c = channel(successDestination)

      c ! Message("a", processorId = 3, sequenceNr = 10)
      c ! Deliver
      c ! Message("b", processorId = 3, sequenceNr = 11)

      dequeue { m => m.posConfirmationTarget must be(journal); m.posConfirmationMessage must be(WriteAck(3, 1, 10)) }
      dequeue { m => m.posConfirmationTarget must be(journal); m.posConfirmationMessage must be(WriteAck(3, 1, 11)) }
    }
    "not set posConfirmationTarget and posConfirmationMessage on delivered message if ack is not required" in { fixture =>
      import fixture._

      val c = channel(successDestination)

      c ! Message("a", processorId = 3, sequenceNr = 10, ack = false)
      c ! Deliver
      c ! Message("b", processorId = 3, sequenceNr = 11, ack = false)
      c ! Message("c", processorId = 3, sequenceNr = 12)

      dequeue { m => m.posConfirmationTarget must be(null) }
      dequeue { m => m.posConfirmationTarget must be(null) }
      dequeue { m => m.posConfirmationTarget must be(journal); m.posConfirmationMessage must be(WriteAck(3, 1, 12)) }
    }
    "not set negConfirmationTarget and negConfirmationMessage on delivered message" in { fixture =>
      import fixture._

      journal ! SetCommandListener(Some(writeAckListener))

      val c = channel(failureDestination("a"))

      c ! Deliver
      c ! Message("a", processorId = 3, sequenceNr = 10)
      c ! Message("b", processorId = 3, sequenceNr = 11)

      // destination only enqueues received messages on success
      dequeue { m => m.negConfirmationTarget must be(null) }

      // doesn't happen because destination calls msg.confirm(false)
      //dequeue(writeAckListenerQueue) must be (WriteAck(3, 1, 10))

      // does happen because destination will msg.confirm(true)
      dequeue(writeAckListenerQueue) must be (WriteAck(3, 1, 11))
    }
    "forward the message sender to the destination" in { fixture =>
      import fixture._

      val c = channel(successDestination)
      val respondTo = request(c) _

      c ! Deliver

      respondTo(Message("a")) must be("re: a")
      respondTo(Message("b")) must be("re: b")
    }
  }
}

object DefaultChannelSpec {
  class Fixture extends EventsourcingFixture[Message] {
    val successDestination = failureDestination(null)
    def failureDestination(failAtEvent: Any) = system.actorOf(Props(new Destination(queue, failAtEvent) with Receiver))

    val writeAckListenerQueue = new LinkedBlockingQueue[WriteAck]
    val writeAckListener = system.actorOf(Props(new WriteAckListener(writeAckListenerQueue)))

    def channel(destination: ActorRef) =
      system.actorOf(Props(new DefaultChannel(1, journal, destination)))

    def message(event: Any, sequenceNr: Long = 0L, ack: Boolean = true) =
      Message(event, sequenceNr = sequenceNr, ack = ack, processorId = 1)
  }

  class Destination(queue: Queue[Message], failAtEvent: Any) extends Actor { this: Receiver =>
    def receive = {
      case event => if (event == failAtEvent) {
        confirm(false)
      } else {
        queue.add(message)
        confirm(true)
        sender ! ("re: %s" format event)
      }
    }
  }

  class WriteAckListener(queue: java.util.Queue[WriteAck]) extends Actor {
    def receive = {
      case cmd: WriteAck => queue.add(cmd)
    }
  }
}
