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

import java.io.File
import java.util.concurrent._

import akka.actor._
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.journal.LeveldbJournal

class CompositeRecoverySpec extends WordSpec with MustMatchers {

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journal = LeveldbJournal(journalDir)

    val extension = EventsourcingExtension(system, journal)

    val processor1 = extension.processorOf(ProcessorProps(1, new Processor1 with Emitter with Eventsourced))
    val processor2 = extension.processorOf(ProcessorProps(2, new Processor2 with Emitter with Eventsourced))

    val destinationQueue = new LinkedBlockingQueue[Message]
    val destination = system.actorOf(Props(new Destination(destinationQueue) with Receiver))

    val echo = system.actorOf(Props(new Echo with Responder))
    val dl = system.deadLetters

    def setupDefaultChannels() {
      extension.channelOf(DefaultChannelProps(1, processor2).withName("processor2"))
      extension.channelOf(DefaultChannelProps(2, echo).withName("echo").withReplyDestination(processor1))
      extension.channelOf(DefaultChannelProps(3, destination).withName("dest"))
    }

    def setupReliableChannels() {
      extension.channelOf(ReliableChannelProps(1, processor2).withName("processor2"))
      extension.channelOf(ReliableChannelProps(2, echo).withName("echo").withReplyDestination(processor1))
      extension.channelOf(ReliableChannelProps(3, destination).withName("dest"))
    }

    def journal(cmd: Any) {
      journal ! cmd
    }

    def dequeue(): Message = {
      destinationQueue.poll(5000, TimeUnit.MILLISECONDS)
    }

    def dequeue(p: Message => Unit) {
      p(dequeue())
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }

    class Processor1 extends Actor { this: Emitter =>
      var numProcessed = 0
      var lastSenderMessageId = 0L

      def receive = {
        case InputCreated(s)  => {
          emitter("processor2").emitEvent(InputModified("%s-%d" format (s, numProcessed)))
          numProcessed = numProcessed + 1
        }
        case InputModified(s) => {
          val sid = senderMessageId.get.toLong
          if (sid <= lastSenderMessageId) { // duplicate detected
            emitter("dest").emitEvent(InputModified("%s-%s" format (s, "dup")))
          } else {
            emitter("dest").emitEvent(InputModified("%s-%d" format (s, numProcessed)))
            numProcessed = numProcessed + 1
            lastSenderMessageId = sid
          }
        }
      }
    }

    class Processor2 extends Actor { this: Emitter =>
      var numProcessed = 0

      def receive = {
        case InputModified(s) => {
          val evt = InputModified("%s-%d" format (s, numProcessed))
          val sid = Some(sequenceNr.toString) // for detecting duplicates

          // emit InputAggregated event to destination with sender message id containing the counted aggregations
          emitter("echo").emit(_.copy(event = evt, senderMessageId = sid))

          numProcessed = numProcessed + 1
        }
      }
    }

    class Echo extends Actor { this: Responder =>
      def receive = {
        case event => responder.send(identity)
      }
    }

    class Destination(queue: LinkedBlockingQueue[Message]) extends Actor { this: Receiver =>
      def receive = {
        case _ => queue.put(message)
      }
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  "An event-sourced composite (directed cyclic processor graph)" when {
    "using reliable channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false))
        // 2.) input message 2 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false))
        // 3.) ACK for input message 1 (written by channel 'processor2')
        journal(WriteAck(1, 1, 1))
        // 4.) output message from processor 1 (written by channel 'processor2' and deleted after delivery)
        //journal(WriteOutMsg(1, Message(InputCreated("a-0"), sequenceNr = 3), 1, SkipAck, dl, false))
        // 5.) output message from processor 1 is now input message 1' for processor 2
        journal(WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 4), dl, false))
        // 6.) ACK for input message 1' (written by channel 'echo')
        journal(WriteAck(2, 2, 4))
        // 7.) output message from processor 2 (written by channel 'echo' and deleted after delivery)
        //journal(WriteOutMsg(2, Message(InputModified("a-0-0"), 2, SkipAck, Some("4"), 5), None, dl, false))
        // 8.) output message from processor 2 is again input message 1'' for processor 1
        journal(WriteInMsg(1, Message(InputModified("a-0-0"), None, Some("4"), 6), dl, false))

        setupReliableChannels()
        extension.recover()

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("4"), m.sequenceNr, 1)) }
        dequeue { m => m must be(Message(InputModified("b-1-1-3"), None, m.senderMessageId, m.sequenceNr, 1)) }

      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false))
        // 2.) input message 2 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false))
        // 3.) ACK for input message 1 (written by channel 'processor2')
        journal(WriteAck(1, 1, 1))
        // 4.) output message from processor 1 (written by channel 'processor2' and deleted after delivery)
        //journal(WriteOutMsg(1, Message(InputCreated("a-0"), sequenceNr = 3), 1, SkipAck, dl, false))
        // 5.) output message from processor 1 is now input message 1' for processor 2
        journal(WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 4), dl, false))
        // 6.) ACK for input message 1' (written by channel 'echo')
        journal(WriteAck(2, 2, 4))
        // 7.) output message from processor 2 (written by channel 'echo')
        // DELIVERED TO NEXT PROCESSOR BUT NOT YET DELETED BY RELIABLE CHANNEL:
        // WILL CAUSE A DUPLICATE (which is detected via senderMessageId)
        journal(WriteOutMsg(2, Message(InputModified("a-0-0"), None, Some("4"), 5), 2, SkipAck, dl, false))
        // 8.) output message from processor 2 is again input message 1'' for processor 1
        journal(WriteInMsg(1, Message(InputModified("a-0-0"), None, Some("4"), 6), dl, false))

        setupReliableChannels()
        extension.recover()

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("4"), m.sequenceNr, 1)) }
        // message order is not preserved when sending echos (i.e responses from echo actor) to reply destination
        Set(dequeue(), dequeue()).map(_.event) must be (Set(InputModified("a-0-0-dup"), InputModified("b-1-1-3")))
      }
    }
    "using default channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false))
        // 2.) input message 2 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false))
        // 3.) output message from processor 1 is now input message 1' for processor 2
        journal(WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 3), dl, false))
        // 4.) ACK that output message of processor 1 has been stored by processor 2
        journal(WriteAck(1, 1, 1))
        // 5.) output message from processor 2 is again input message 1'' for processor 1
        journal(WriteInMsg(1, Message(InputModified("a-0-0"), None, Some("3"), 4), dl, false))
        // 6.) ACK that output message of processor 2 has been stored by processor 1
        journal(WriteAck(2, 2, 3))

        setupDefaultChannels()
        extension.recover()

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("3"), m.sequenceNr, 1)) }
        dequeue { m => m must be(Message(InputModified("b-1-1-3"), None, m.senderMessageId, m.sequenceNr, 1)) }

      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        //  example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("a"), sequenceNr = 1), dl, false))
        // 2.) input message 2 written by processor 1
        journal(WriteInMsg(1, Message(InputCreated("b"), sequenceNr = 2), dl, false))
        // 3.) output message from processor 1 is now input message 1' for processor 2
        journal(WriteInMsg(2, Message(InputModified("a-0"), sequenceNr = 3), dl, false))
        // 4.) ACK that output message of processor 1 has been stored by processor 2
        journal(WriteAck(1, 1, 1))
        // 5.) output message from processor 2 is again input message 1'' for processor 1
        journal(WriteInMsg(1, Message(InputModified("a-0-0"), None, Some("3"), 4), dl, false))
        // 6.) ACK that output message of processor 2 has been stored by processor 1
        // NOT YET ACKNOWLEDGED: WILL CAUSE A DUPLICATE (which is detected via senderMessageId)
        //journal(WriteAck(2, 2, 3))

        setupDefaultChannels()
        extension.recover()

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("3"), m.sequenceNr, 1)) }
        // message order is not preserved when sending echos (i.e responses from echo actor) to reply destination
        Set(dequeue(), dequeue()).map(_.event) must be (Set(InputModified("a-0-0-dup"), InputModified("b-1-1-3")))
      }
    }
  }
}

case class InputCreated(s: String)
case class InputModified(s: String)
