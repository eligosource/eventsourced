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

import GraphRecoverySpec._

class GraphRecoverySpec extends EventsourcingSpec[Fixture] {

  // =====================================================
  //  Event-sourced directed cyclic graph:
  //
  //       ----------------------------------
  //      |                                  |
  //      v                                  |
  //  processor1 ------> processor2  -----> echo
  //      |
  //      v
  //  destination
  //
  // =====================================================

  "An event-sourced directed cyclic processor graph" when {
    "using reliable channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false)
        // 2.) input message 2 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false)
        // 3.) ACK for input message 1 (written by channel 'processor2')
        journal ! WriteAck(1, 1, 1)
        // 4.) output message from processor 1 (written by channel 'processor2' and deleted after delivery)
        //journal ! WriteOutMsg(1, Message(InputCreated("a-0"), sequenceNr = 3), 1, SkipAck, dl, false)
        // 5.) output message from processor 1 is now input message 1' for processor 2
        journal ! WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 4), dl, false)
        // 6.) ACK for input message 1' (written by channel 'echo')
        journal ! WriteAck(2, 2, 4)
        // 7.) output message from processor 2 (written by channel 'echo' and deleted after delivery)
        //journal ! WriteOutMsg(2, Message(InputModified("a-0-0"), 2, SkipAck, Some("4"), 5), None, dl, false)
        // 8.) output message from processor 2 is again input message 1'' for processor 1
        journal ! WriteInMsg(1, Message(InputModified("a-0-0", 4), 6), dl, false)

        setupReliableChannels()
        extension.recover()

        dequeue { m => m.event must be(InputModified("a-0-0-2")) }
        dequeue { m => m.event must be(InputModified("b-1-1-3")) }
      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false)
        // 2.) input message 2 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false)
        // 3.) ACK for input message 1 (written by channel 'processor2')
        journal ! WriteAck(1, 1, 1)
        // 4.) output message from processor 1 (written by channel 'processor2' and deleted after delivery)
        //journal ! WriteOutMsg(1, Message(InputCreated("a-0"), sequenceNr = 3), 1, SkipAck, dl, false)
        // 5.) output message from processor 1 is now input message 1' for processor 2
        journal ! WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 4), dl, false)
        // 6.) ACK for input message 1' (written by channel 'echo')
        journal ! WriteAck(2, 2, 4)
        // 7.) output message from processor 2 (written by channel 'echo')
        // DELIVERED TO NEXT PROCESSOR BUT NOT YET DELETED BY RELIABLE CHANNEL:
        // WILL CAUSE A DUPLICATE (which is detected via InputModified.msgId field)
        journal ! WriteOutMsg(2, Message(InputModified("a-0-0", 4), 5), 2, SkipAck, dl, false)
        // 8.) output message from processor 2 is again input message 1'' for processor 1
        journal ! WriteInMsg(1, Message(InputModified("a-0-0", 4), 6), dl, false)

        setupReliableChannels()
        extension.recover()

        dequeue { m => m.event must be(InputModified("a-0-0-2")) }
        dequeue { m => m.event must be(InputModified("a-0-0-dup")) }
        dequeue { m => m.event must be(InputModified("b-1-1-3")) }
      }
    }
    "using default channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false)
        // 2.) input message 2 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false)
        // 3.) output message from processor 1 is now input message 1' for processor 2
        journal ! WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 3), dl, false)
        // 4.) ACK that output message of processor 1 has been stored by processor 2
        journal ! WriteAck(1, 1, 1)
        // 5.) output message from processor 2 is again input message 1'' for processor 1
        journal ! WriteInMsg(1, Message(InputModified("a-0-0", 3), 4), dl, false)
        // 6.) ACK that output message of processor 2 has been stored by processor 1
        journal ! WriteAck(2, 2, 3)

        setupDefaultChannels()
        extension.recover()

        dequeue { m => m.event must be(InputModified("a-0-0-2")) }
        dequeue { m => m.event must be(InputModified("b-1-1-3")) }
      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false)
        // 2.) input message 2 written by processor 1
        journal ! WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false)
        // 3.) output message from processor 1 is now input message 1' for processor 2
        journal ! WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 3), dl, false)
        // 4.) ACK that output message of processor 1 has been stored by processor 2
        journal ! WriteAck(1, 1, 1)
        // 5.) output message from processor 2 is again input message 1'' for processor 1
        journal ! WriteInMsg(1, Message(InputModified("a-0-0", 3), 4), dl, false)
        // 6.) ACK that output message of processor 2 has been stored by processor 1
        // NOT YET ACKNOWLEDGED: WILL CAUSE A DUPLICATE (which is detected via InputModified.msgId field)
        //journal ! WriteAck(2, 2, 3)

        setupDefaultChannels()
        extension.recover()

        dequeue { m => m.event must be(InputModified("a-0-0-2")) }
        dequeue { m => m.event must be(InputModified("a-0-0-dup")) }
        dequeue { m => m.event must be(InputModified("b-1-1-3")) }
      }
    }
  }
}

object GraphRecoverySpec {
  class Fixture extends EventsourcingFixture[Message] {
    val dl = system.deadLetters

    val processor1 = extension.processorOf(Props(new Processor1 with Emitter with Confirm with Eventsourced { val id = 1 } ))
    val processor2 = extension.processorOf(Props(new Processor2 with Emitter with Confirm with Eventsourced { val id = 2 } ))

    val echo = system.actorOf(Props(new Echo(processor1) with Confirm))
    val destination = system.actorOf(Props(new Destination(queue) with Confirm))

    def setupDefaultChannels() {
      extension.channelOf(DefaultChannelProps(1, processor2).withName("processor2"))
      extension.channelOf(DefaultChannelProps(2, echo).withName("echo"))
      extension.channelOf(DefaultChannelProps(3, destination).withName("dest"))
    }

    def setupReliableChannels() {
      extension.channelOf(ReliableChannelProps(1, processor2).withName("processor2"))
      extension.channelOf(ReliableChannelProps(2, echo).withName("echo"))
      extension.channelOf(ReliableChannelProps(3, destination).withName("dest"))
    }
  }

  case class InputCreated(s: String)
  case class InputModified(s: String, msgId: Long = 0L)

  class Processor1 extends Actor { this: Emitter =>
    var numProcessed = 0
    var lastMessageId = 0L

    def receive = {
      case InputCreated(s)  => {
        emitter("processor2") sendEvent InputModified("%s-%d" format (s, numProcessed))
        numProcessed = numProcessed + 1
      }
      case InputModified(s, msgId) => {
        if (msgId <= lastMessageId) { // duplicate detected
          emitter("dest") sendEvent InputModified("%s-%s" format (s, "dup"))
        } else {
          emitter("dest") sendEvent InputModified("%s-%d" format (s, numProcessed))
          numProcessed = numProcessed + 1
          lastMessageId = msgId
        }
      }
    }
  }

  class Processor2 extends Actor { this: Emitter =>
    var numProcessed = 0

    def receive = {
      case InputModified(s, _) => {
        emitter("echo") sendEvent InputModified("%s-%d" format (s, numProcessed), sequenceNr)
        numProcessed = numProcessed + 1
      }
    }
  }

  class Echo(target: ActorRef) extends Actor {
    def receive = {
      case msg: Message => target ! msg
    }
  }

  class Destination(queue: java.util.Queue[Message]) extends Actor {
    def receive = {
      case msg: Message => queue.add(msg)
    }
  }
}
