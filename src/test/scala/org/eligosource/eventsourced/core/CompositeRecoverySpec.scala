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
import akka.dispatch.Await
import akka.pattern.ask
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

    val destinationQueue = new LinkedBlockingQueue[Message]
    val destination = system.actorOf(Props(new Receiver(destinationQueue)))

    // Example of an external service that echoes its input. The echo
    // is fed back to component 1 (c1) of the example composite.
    val echo = system.actorOf(Props(new Echo))

    val dl = system.deadLetters

    def createExampleComposite(journal: ActorRef, destination: ActorRef, reliable: Boolean): Component = {
      val c1 = Component(1, journal)
      val c2 = Component(2, journal)

      if (reliable) {
        c1.addReliableOutputChannelToComponent("next", c2)
        c2.addReliableOutputChannelToActor("next", echo, Some(c1))
        c1.addReliableOutputChannelToActor("dest", destination)
      } else {
        c1.addDefaultOutputChannelToComponent("next", c2)
        c2.addReliableOutputChannelToActor("next", echo, Some(c1))
        c1.addDefaultOutputChannelToActor("dest", destination)
      }

      c2.setProcessor(outputChannels => system.actorOf(Props(new C2Processor(outputChannels))))
      c1.setProcessor(outputChannels => system.actorOf(Props(new C1Processor(outputChannels))))
    }

    def journal(cmd: Any) {
      Await.result(journal ? cmd, timeout.duration)
    }

    def dequeue(p: Message => Unit) {
      p(destinationQueue.poll(5000, TimeUnit.MILLISECONDS))
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
    }

    class C1Processor(outputChannels: Map[String, ActorRef]) extends Actor {
      var numProcessed = 0
      var lastSenderMessageId = 0L

      def receive = {
        case msg: Message => msg.event match {
          case InputCreated(s)  => {
            outputChannels("next") ! msg.copy(event = InputModified("%s-%d" format (s, numProcessed)))
            numProcessed = numProcessed + 1
          }
          case InputModified(s) => {
            val sid = msg.senderMessageId.get.toLong
            if (sid <= lastSenderMessageId) { // duplicate detected
              outputChannels("dest") ! msg.copy(event = InputModified("%s-%s" format (s, "dup")))
            } else {
              outputChannels("dest") ! msg.copy(event = InputModified("%s-%d" format (s, numProcessed)))
              numProcessed = numProcessed + 1
              lastSenderMessageId = sid
            }
          }
        }
      }
    }

    class C2Processor(outputChannels: Map[String, ActorRef]) extends Actor {
      var numProcessed = 0

      def receive = {
        case msg: Message => msg.event match {
          case InputModified(s) => {
            val evt = InputModified("%s-%d" format (s, numProcessed))
            val sid = Some(msg.sequenceNr.toString) // for detecting duplicates
            outputChannels("next") ! msg.copy(event = evt, senderMessageId = sid)
            numProcessed = numProcessed + 1
          }
        }
      }
    }

    class Echo extends Actor {
      def receive = { case msg: Message => sender ! msg }
    }

    class Receiver(queue: LinkedBlockingQueue[Message]) extends Actor {
      def receive = {
        case msg: Message => { queue.put(msg); sender ! Ack }
      }
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  "An event-sourced composite (directed cyclic component graph)" when {
    "using reliable output channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        // AggregatorExample journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("a"), sequenceNr = 1), None, dl, false))
        // 2.) input message 2 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("b"), sequenceNr = 2), None, dl, false))
        // 3.) ACK that input message 1 has been processed by processor 1
        journal(WriteAck(1, 1, 1))
        // 4.) output message from processor 1 written by 'next' output channel of component 1 (and deleted after delivery)
        //journal(WriteMsg(1, 1, Message(InputCreated("a-0"), sequenceNr = 3), None, dl, false))
        // 5.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(2, 0, Message(InputModified("a-0"), sequenceNr = 4), None, dl, false))
        // 6.) ACK that input message 1' has been processed by processor 2
        journal(WriteAck(2, 1, 4))
        // 7.) output message from processor 2 written by 'next' output channel of component 2 (and deleted after delivery)
        //journal(WriteMsg(2, 1, Message(InputModified("a-0-0"), None, Some("4"), 5), None, dl, false))
        // 8.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(1, 0, Message(InputModified("a-0-0"), None, Some("4"), 6), None, dl, false))

        val composite = createExampleComposite(journal, destination, true)

        Composite.init(composite)

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("4"), m.sequenceNr)) }
        dequeue { m => m must be(Message(InputModified("b-1-1-3"), None, m.senderMessageId, m.sequenceNr)) }

      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        // AggregatorExample journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("a"), sequenceNr = 1), None, dl, false))
        // 2.) input message 2 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("b"), sequenceNr = 2), None, dl, false))
        // 3.) ACK that input message 1 has been processed by processor 1
        journal(WriteAck(1, 1, 1))
        // 4.) output message from processor 1 written by 'next' output channel of component 1 (and deleted after delivery)
        //journal(WriteMsg(1, 1, Message(InputCreated("a-0"), sequenceNr = 3), None, dl, false))
        // 5.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(2, 0, Message(InputModified("a-0"), sequenceNr = 4), None, dl, false))
        // 6.) ACK that input message 1' has been processed by processor 2
        journal(WriteAck(2, 1, 4))
        // 7.) output message from processor 2 written by 'next' output channel of component 2
        // DELIVERED TO NEXT COMPONENT BUT NOT YET DELETED FROM RELIABLE OUTPUT CHANNEL:
        // WILL CAUSE A DUPLICATE (which can be detected via senderMessageId and ignored, if needed)
        journal(WriteMsg(2, 1, Message(InputModified("a-0-0"), None, Some("4"), 5), None, dl, false))
        // 8.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(1, 0, Message(InputModified("a-0-0"), None, Some("4"), 6), None, dl, false))

        val composite = createExampleComposite(journal, destination, true)

        Composite.init(composite)

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("4"), m.sequenceNr)) }
        dequeue { m => m must be(Message(InputModified("a-0-0-dup"), None, Some("4"), m.sequenceNr)) }
        dequeue { m => m must be(Message(InputModified("b-1-1-3"), None, m.senderMessageId, m.sequenceNr)) }
      }
    }
    "using default output channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        // AggregatorExample journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("a"), sequenceNr = 1), None, dl, false))
        // 2.) input message 2 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("b"), sequenceNr = 2), None, dl, false))
        // 3.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(2, 0, Message(InputModified("a-0"), sequenceNr = 3), None, dl, false))
        // 4.) ACK that input message 1 has been processed by processor 1 (and stored by component 2)
        journal(WriteAck(1, 1, 1))
        // 5.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(1, 0, Message(InputModified("a-0-0"), None, Some("3"), 4), None, dl, false))
        // 6.) ACK that input message 1' has been processed by processor 2
        journal(WriteAck(2, 1, 3))

        val composite = createExampleComposite(journal, destination, false)

        Composite.init(composite)

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("3"), m.sequenceNr)) }
        dequeue { m => m must be(Message(InputModified("b-1-1-3"), None, m.senderMessageId, m.sequenceNr)) }

      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        // AggregatorExample journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("a"), sequenceNr = 1), None, dl, false))
        // 2.) input message 2 written by input channel of component 1
        journal(WriteMsg(1, 0, Message(InputCreated("b"), sequenceNr = 2), None, dl, false))
        // 3.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(2, 0, Message(InputModified("a-0"), sequenceNr = 3), None, dl, false))
        // 4.) ACK that input message 1 has been processed by processor 1 (and stored by component 2)
        journal(WriteAck(1, 1, 1))
        // 5.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(1, 0, Message(InputModified("a-0-0"), None, Some("3"), 4), None, dl, false))
        // 6.) ACK that input message 1' has been processed by processor 2
        // NOT YET ACKNOWLEDGED: WILL CAUSE A DUPLICATE (which is detected)
        //journal(WriteAck(2, 1, 3))

        val composite = createExampleComposite(journal, destination, false)

        Composite.init(composite)

        dequeue { m => m must be(Message(InputModified("a-0-0-2"), None, Some("3"), m.sequenceNr)) }
        dequeue { m => m must be(Message(InputModified("a-0-0-dup"), None, Some("3"), m.sequenceNr)) }
        dequeue { m => m must be(Message(InputModified("b-1-1-3"), None, m.senderMessageId, m.sequenceNr)) }
      }
    }
  }
}

case class InputCreated(s: String)
case class InputModified(s: String)
