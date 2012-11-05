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

import scala.concurrent.duration._

import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue

import akka.actor._

import org.eligosource.eventsourced.core.ReliableChannelSpec._

class ReliableChannelSpec extends EventsourcingSpec[Fixture] {
  "A reliable channel" when {
    "deliver already stored output messages" in { fixture =>
      import fixture._

      writeOutMsg(Message("a", sequenceNr = 4L))
      writeOutMsg(Message("b", sequenceNr = 5L))

      val c = channel(successDestination)

      c ! Deliver

      dequeue match { case Right(msg) => { msg.event must be("a"); msg.sequenceNr must be(4L) } }
      dequeue match { case Right(msg) => { msg.event must be("b"); msg.sequenceNr must be(5L) } }
    }
    "store and deliver new output messages" in { fixture =>
      import fixture._

      val c = channel(successDestination)

      journal ! SetCommandListener(Some(writeOutMsgListener))

      c ! Deliver
      c ! Message("a", processorId = 3, sequenceNr = 10L)
      c ! Message("b", processorId = 4, sequenceNr = 11L)

      dequeue(writeOutMsgListenerQueue) match {
        case WriteOutMsg(_, m, ackProcessorId, ackSequenceNr, _, _) => {
          m.event must be("a"); ackProcessorId must be(3); ackSequenceNr must be(10L)
        }
      }
      dequeue(writeOutMsgListenerQueue) match {
        case WriteOutMsg(_, m, ackProcessorId, ackSequenceNr, _, _) => {
          m.event must be("b"); ackProcessorId must be(4); ackSequenceNr must be(11L)
        }
      }

      dequeue match { case Right(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue match { case Right(msg) => { msg.event must be("b"); msg.sequenceNr must be(2L) } }
    }
    "prevent writing an acknowledgement if requested" in { fixture =>
      import fixture._

      val c = channel(successDestination)

      journal ! SetCommandListener(Some(writeOutMsgListener))

      c ! Deliver
      c ! Message("a", processorId = 3, sequenceNr = 10L, ack = false)
      c ! Message("b", processorId = 4, sequenceNr = 11L)

      dequeue(writeOutMsgListenerQueue) match {
        case WriteOutMsg(_, _, _, ackSequenceNr, _, _) => ackSequenceNr must be(SkipAck)
      }
      dequeue(writeOutMsgListenerQueue) match {
        case WriteOutMsg(_, _, _, ackSequenceNr, _, _) => ackSequenceNr must be(11L)
      }
    }
    "forward the message sender to the destination" in { fixture =>
      import fixture._

      val c = channel(successDestination)
      val respondTo = request(c) _

      c ! Deliver

      respondTo(Message("a")) must be("re: a")
      respondTo(Message("b")) must be("re: b")
    }
    "forward the message sender to the destination before reaching maxRetry" in { fixture =>
      import fixture._

      val c = channel(failureDestination(failAtEvent = "a", enqueueFailures = true, failureCount = 2))
      val respondTo = request(c) _

      c ! Deliver

      respondTo(Message("a")) must be("re: a")

      dequeue match { case Left(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue match { case Left(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue match { case Right(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
    }
    "forward deadLetters to the destination after reaching maxRetry" in { fixture =>
      import fixture._

      val d = failureDestination(failAtEvent = "a", enqueueFailures = true, failureCount = 4)
      val c = channel(d)

      val dlq = new LinkedBlockingQueue[Either[Message, Message]]

      // sender is not persisted and channel is re-started after reaching maxRertry
      // response will go to deadLetters which is then published on event stream
      system.eventStream.subscribe(system.actorOf(Props(new Actor {
        def receive = { case DeadLetter(response, _, _) => dlq add Right(Message(response)) }
      })), classOf[DeadLetter])

      c ! Deliver
      c ! Message("a")

      dequeue      match { case Left(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue      match { case Left(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue      match { case Left(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue      match { case Left(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue      match { case Right(msg) => { msg.event must be("a"); msg.sequenceNr must be(1L) } }
      dequeue(dlq) match { case Right(msg) => msg.event must be("re: a") } // added via event stream subscriber
    }
    "forward deadLetters to the destination before it is initialized" in { fixture =>
      import fixture._

      val d = successDestination
      val c = channel(d)

      val dlq = new LinkedBlockingQueue[Either[Message, Message]]

      // sender is not persisted and channel doesn't deliver before initialization
      // response will go to deadLetters which is then published on event stream
      system.eventStream.subscribe(system.actorOf(Props(new Actor {
        def receive = { case DeadLetter(response, _, _) => dlq add Right(Message(response)) }
      })), classOf[DeadLetter])

      c ! Message("a")
      c ! Deliver

      dequeue(dlq)
      dequeue      match { case Right(msg) => msg.event must be("a") }
      dequeue(dlq) match { case Right(msg) => msg.event must be("re: a") }
    }
    "recover from destination failures and preserve message order" in { fixture =>
      import fixture._

      val c = channel(failureDestination(failAtEvent = 4, enqueueFailures = true, failureCount = 5))

      c ! Deliver

      1 to 7 foreach { i => c ! Message(i) }

      val expected = List(
        Right(Message(1, sequenceNr = 1L)), // success    at event 1
        Right(Message(2, sequenceNr = 2L)), // success    at event 2
        Right(Message(3, sequenceNr = 3L)), // success    at event 3
        Left( Message(4, sequenceNr = 4L)), // failure #1 at event 4
        Left( Message(4, sequenceNr = 4L)), // failure #2 at event 4, retry #1 before recovery
        Left( Message(4, sequenceNr = 4L)), // failure #3 at event 4, retry #2 before recovery
        Left( Message(4, sequenceNr = 4L)), // failure #4 at event 4, retry #3 before recovery
        Left( Message(4, sequenceNr = 4L)), // failure #5 at event 4, retry #1 after recovery #1
        Right(Message(4, sequenceNr = 4L)), // success    at event 4, retry #2 after recovery #1
        Right(Message(5, sequenceNr = 5L)), // success    at event 5
        Right(Message(6, sequenceNr = 6L)), // success    at event 6
        Right(Message(7, sequenceNr = 7L))  // success    at event 7
      )

      List.fill(12)(dequeue() match {
        case Left(msg)  => Left(Message(msg.event, sequenceNr = msg.sequenceNr))
        case Right(msg) => Right(Message(msg.event, sequenceNr = msg.sequenceNr))
      }) must be(expected)

      // send another message to reliable channel
      c ! Message(8, sequenceNr = 0L)

      // check that sequence number is updated appropriately
      dequeue() match { case Right(msg) => { msg.event must be(8); msg.sequenceNr must be(8L) } }
    }
  }
}

object ReliableChannelSpec {
  class Fixture extends EventsourcingFixture[Either[Message, Message]] {
    val responder = system.actorOf(Props[WriteOutMsgResponder])

    val successDestination =
      system.actorOf(Props(new Destination(queue, null, 0, true) with Receiver))
    def failureDestination(failAtEvent: Any, enqueueFailures: Boolean, failureCount: Int) =
      system.actorOf(Props(new Destination(queue, failAtEvent, failureCount, enqueueFailures) with Receiver))

    val writeOutMsgListenerQueue = new LinkedBlockingQueue[WriteOutMsg]
    val writeOutMsgListener = system.actorOf(Props(new WriteOutMsgListener(writeOutMsgListenerQueue)))

    val policy = new RedeliveryPolicy(10 milliseconds, 10 milliseconds, 3)
    def channel(destination: ActorRef) = system.actorOf(Props(new ReliableChannel(1, journal, destination, policy)))

    /** Synchronous write of out message to journal. */
    def writeOutMsg(msg: Message) {
      val ackSequenceNr: Long = if (msg.ack) msg.sequenceNr else SkipAck
      request(journal)(WriteOutMsg(1, msg, msg.processorId, ackSequenceNr, responder, false))
    }
  }

  class Destination(queue: Queue[Either[Message, Message]], failAtEvent: Any, var failureCount: Int, enqueueFailures: Boolean) extends Actor { this: Receiver =>
    def receive = {
      /*case DeadLetter(response, _, _) => {
        queue.add(Right(Message(response)))
      }*/
      case event => {
        if (failAtEvent == event && failureCount > 0) {
          failureCount = failureCount - 1
          confirm(false)
          if (enqueueFailures) queue.add(Left(message))
        } else {
          queue.add(Right(message))
          confirm(true)
          sender ! ("re: %s" format event)
        }
      }
    }
  }

  class WriteOutMsgListener(queue: Queue[WriteOutMsg]) extends Actor {
    def receive = {
      case cmd: WriteOutMsg => queue.add(cmd)
    }
  }

  class WriteOutMsgResponder extends Actor {
    def receive = {
      case _ => sender ! ()
    }
  }
}
