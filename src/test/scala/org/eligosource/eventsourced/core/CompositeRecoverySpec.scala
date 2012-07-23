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

class CompositeRecoverySpec extends WordSpec with MustMatchers {
  import Journaler._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journalDir = new File("target/journal")
    val journaler = system.actorOf(Props(new Journaler(journalDir)))

    val queue = new LinkedBlockingQueue[Message]
    val destination = system.actorOf(Props(new Receiver(queue)))

    val dl = system.deadLetters

    def createExampleComposite(journaler: ActorRef, destination: ActorRef, reliable: Boolean): Component = {
      val c1 = Component(0, journaler)
      val c2 = Component(1, journaler)

      if (reliable) {
        c1.addReliableOutputChannelToComponent("next", c2)
        c2.addReliableOutputChannelToComponent("next", c1)
        c1.addReliableOutputChannelToActor("dest", destination)
      } else {
        c1.addDefaultOutputChannelToComponent("next", c2)
        c2.addDefaultOutputChannelToComponent("next", c1)
        c1.addDefaultOutputChannelToActor("dest", destination)
      }

      c2.setProcessor(outputChannels => system.actorOf(Props(new C2Processor(outputChannels))))
      c1.setProcessor(outputChannels => system.actorOf(Props(new C1Processor(outputChannels))))
    }

    def journal(cmd: Any) {
      Await.result(journaler ? cmd, timeout.duration)
    }

    def dequeue(timeout: Long = 5000): Message = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }

    def shutdown() {
      system.shutdown()
      system.awaitTermination(5 seconds)
      FileUtils.deleteDirectory(journalDir)
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
        // Example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(Key(0, 0, 1, 0), Message(InputCreated("a"), None, None, 1), dl))
        // 2.) input message 2 written by input channel of component 2
        journal(WriteMsg(Key(0, 0, 2, 0), Message(InputCreated("b"), None, None, 2), dl)) // input message 2
        // 3.) ACK that input message 1 has been processed by processor 1 (and stored by out-channel)
        journal(WriteAck(Key(0, 0, 1, 1)))
        // 4.) output message from processor 1 written by 'next' output channel of component 1 (deleted after delivery)
        //journal(WriteMsg(Key(0, 1, 1, 0), Message(InputModified("a-0"), None, None, 1)))
        // 5.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(Key(1, 0, 1, 0), Message(InputModified("a-0"), None, None, 1), dl))
        // 6.) ACK that input message 1' has been processed by processor 2 (and stored by out-channel)
        journal(WriteAck(Key(1, 0, 1, 1))) // instead
        // 7.) output message from processor 2 written by 'next' output channel of component 2
        //journal(WriteMsg(Key(1, 1, 1, 0), Message(InputModified("a-0-0"), None, Some("1"), 1)))
        // 8.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(Key(0, 0, 3, 0), Message(InputModified("a-0-0"), None, Some("1"), 3), dl))

        val composite = createExampleComposite(journaler, destination, true)

        composite.foreach(_.replay())
        composite.foreach(_.deliver())

        dequeue() must be(Message(InputModified("a-0-0-2"), None, Some("1"), 1))
        dequeue() must be(Message(InputModified("b-1-1-3"), None, Some("2"), 2))
      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        // Example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(Key(0, 0, 1, 0), Message(InputCreated("a"), None, None, 1), dl))
        // 2.) input message 2 written by input channel of component 2
        journal(WriteMsg(Key(0, 0, 2, 0), Message(InputCreated("b"), None, None, 2), dl)) // input message 2
        // 3.) ACK that input message 1 has been processed by processor 1 (and stored by out-channel)
        journal(WriteAck(Key(0, 0, 1, 1)))
        // 4.) output message from processor 1 written by 'next' output channel of component 1 (deleted after delivery)
        //journal(WriteMsg(Key(0, 1, 1, 0), Message(InputModified("a-0"), None, None, 1)))
        // 5.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(Key(1, 0, 1, 0), Message(InputModified("a-0"), None, None, 1), dl))
        // 6.) ACK that input message 1' has been processed by processor 2 (and stored by out-channel)
        journal(WriteAck(Key(1, 0, 1, 1))) // instead
        // 7.) output message from processor 2 written by 'next' output channel of component 2
        // DELIVERED TO NEXT COMPONENT BUT NOT YET DELETED FROM RELIABLE OUTPUT CHANNEL:
        // WILL CAUSE A DUPLICATE (which can be detected via senderMessageId and ignored, if needed)
        journal(WriteMsg(Key(1, 1, 1, 0), Message(InputModified("a-0-0"), None, Some("1"), 1), dl))
        // 8.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(Key(0, 0, 3, 0), Message(InputModified("a-0-0"), None, Some("1"), 3), dl))

        val composite = createExampleComposite(journaler, destination, true)

        composite.foreach(_.replay())
        composite.foreach(_.deliver())

        dequeue() must be(Message(InputModified("a-0-0-2"),   None, Some("1"), 1))
        dequeue() must be(Message(InputModified("a-0-0-dup"), None, Some("1"), 2))
        dequeue() must be(Message(InputModified("b-1-1-3"),   None, Some("2"), 3))
      }
    }
    "using default output channels" must {
      "recover from failures" in { fixture =>
        import fixture._

        // ----------------------------------
        // Example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(Key(0, 0, 1, 0), Message(InputCreated("a"), None, None, 1), dl))
        // 2.) input message 2 written by input channel of component 2
        journal(WriteMsg(Key(0, 0, 2, 0), Message(InputCreated("b"), None, None, 2), dl)) // input message 2
        // 3.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(Key(1, 0, 1, 0), Message(InputModified("a-0"), None, None, 1), dl))
        // 4.) ACK that input message 1 has been processed by processor 1 (and stored by component 2)
        journal(WriteAck(Key(0, 0, 1, 1)))
        // 5.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(Key(0, 0, 3, 0), Message(InputModified("a-0-0"), None, Some("1"), 3), dl))
        // 6.) ACK that input message 1' has been processed by processor 2
        journal(WriteAck(Key(1, 0, 1, 1)))

        val composite = createExampleComposite(journaler, destination, false)

        composite.foreach(_.replay())
        composite.foreach(_.deliver())

        dequeue() must be(Message(InputModified("a-0-0-2"), None, Some("1"), 1))
        dequeue() must be(Message(InputModified("b-1-1-3"), None, Some("2"), 2))
      }
      "recover from failures and support duplicate detection" in { fixture =>
        import fixture._

        // ----------------------------------
        // Example journal state after crash
        // ----------------------------------

        // 1.) input message 1 written by input channel of component 1
        journal(WriteMsg(Key(0, 0, 1, 0), Message(InputCreated("a"), None, None, 1), dl))
        // 2.) input message 2 written by input channel of component 2
        journal(WriteMsg(Key(0, 0, 2, 0), Message(InputCreated("b"), None, None, 2), dl)) // input message 2
        // 3.) output message from processor 1 is now input message 1' of component 2
        journal(WriteMsg(Key(1, 0, 1, 0), Message(InputModified("a-0"), None, None, 1), dl))
        // 4.) ACK that input message 1 has been processed by processor 1 (and stored by component 2)
        journal(WriteAck(Key(0, 0, 1, 1)))
        // 5.) output message from processor 2 is again input message 1'' of component 1
        journal(WriteMsg(Key(0, 0, 3, 0), Message(InputModified("a-0-0"), None, Some("1"), 3), dl))
        // 6.) ACK that input message 1' has been processed by processor 2
        // NOT YET ACKNOWLEDGED: WILL CAUSE A DUPLICATE (which is detected)
        //journal(WriteAck(Key(1, 0, 1, 1)))

        val composite = createExampleComposite(journaler, destination, false)

        composite.foreach(_.replay())
        composite.foreach(_.deliver())

        dequeue() must be(Message(InputModified("a-0-0-2"),   None, Some("1"), 1))
        dequeue() must be(Message(InputModified("a-0-0-dup"), None, Some("1"), 2))
        dequeue() must be(Message(InputModified("b-1-1-3"),   None, Some("2"), 3))
      }
    }
  }
}

case class InputCreated(s: String)
case class InputModified(s: String)

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

class Receiver(queue: LinkedBlockingQueue[Message]) extends Actor {
  def receive = {
    case msg: Message => {
      queue.put(msg)
      sender ! ()
    }
  }
}
