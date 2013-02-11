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

import scala.concurrent.duration._

import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue

import akka.actor._

import org.eligosource.eventsourced.core.Channel._
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.core.ReliableChannelSpec._

class ReliableChannelSpec extends EventsourcingSpec[Fixture] {
  "A reliable channel" must {
    "deliver already stored output messages" in { fixture =>
      import fixture._

      writeOutMsg(Message("a", sequenceNr = 4L))
      writeOutMsg(Message("b", sequenceNr = 5L))

      val c = channel(successDestination)

      c ! Deliver

      dq() must be (Right(Message("a", sequenceNr = 4L)))
      dq() must be (Right(Message("b", sequenceNr = 5L)))
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

      dq() must be (Right(Message("a", sequenceNr = 1L)))
      dq() must be (Right(Message("b", sequenceNr = 2L)))
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
      val respondTo = result[String](c) _

      c ! Deliver

      respondTo(Message("a")) must be("re: a")
      respondTo(Message("b")) must be("re: b")
    }
    "preserve the message sender before it is initialized" in { fixture =>
      import scala.concurrent._
      import akka.pattern.ask
      import fixture._

      val c = channel(successDestination)
      val future = c ? Message("a")

      c ! Deliver

      Await.result(future, timeout.duration) must be("re: a")
    }
    "preserve the message sender before reaching maxRedelivery" in { fixture =>
      import fixture._

      val c = channel(failureDestination(failAtEvent = "a", enqueueFailures = true, failureCount = 2))
      val respondTo = result[String](c) _

      c ! Deliver

      respondTo(Message("a")) must be("re: a")

      dq() must be (Left(Message("a", sequenceNr = 1L)))
      dq() must be (Left(Message("a", sequenceNr = 1L)))
      dq() must be (Right(Message("a", sequenceNr = 1L)))
    }
    "preserve the message sender reaching maxRedelivery" in { fixture =>
      import fixture._

      val c = channel(failureDestination(failAtEvent = "a", enqueueFailures = true, failureCount = 4))
      val respondTo = result[String](c) _

      c ! Deliver

      respondTo(Message("a")) must be("re: a")

      dq() must be (Left(Message("a", sequenceNr = 1L)))
      dq() must be (Left(Message("a", sequenceNr = 1L)))
      dq() must be (Left(Message("a", sequenceNr = 1L)))
      dq() must be (Left(Message("a", sequenceNr = 1L)))
      dq() must be (Right(Message("a", sequenceNr = 1L)))
    }
    "tolerate invalid actor paths" in { fixture =>
      import fixture._

      class Destination extends Actor { this: Receiver =>
        def receive = { case event => sender ! event }
      }

      val dlq = new LinkedBlockingQueue[Any]

      writeOutMsg(Message("a", sequenceNr = 1L, senderPath = "akka://test/temp/x"))
      writeOutMsg(Message("b", sequenceNr = 2L, senderPath = "akka://test/user/y"))

      system.eventStream.subscribe(system.actorOf(Props(new Actor {
        def receive = { case DeadLetter(response, _, _) => dlq add response }
      })), classOf[DeadLetter])

      val d = system.actorOf(Props(new Destination with Receiver with Confirm))
      val c = channel(d)

      c ! Deliver

      dequeue(dlq) must be("a")
      dequeue(dlq) must be("b")
    }
    "publish DeliveryStopped to event stream when reaching restartMax" in { fixture =>
      import fixture._

      class Destination extends Actor { this: Receiver =>
        def receive = { case event => confirm(false) }
      }

      val dlq = new LinkedBlockingQueue[Int]

      writeOutMsg(Message("a", sequenceNr = 1L))

      system.eventStream.subscribe(system.actorOf(Props(new Actor {
        def receive = { case DeliveryStopped(channelId) => dlq add channelId }
      })), classOf[DeliveryStopped])

      val d = system.actorOf(Props(new Destination with Receiver))
      val c = channel(d)

      c ! Deliver

      dequeue(dlq) must be(1)
    }
    "reset restart counter after positive confirmation" in { fixture =>
      import fixture._

      class Destination extends Actor { this: Receiver =>
        var ctr = 0

        def receive = {
          case event => {
            ctr += 1
            if (ctr == 6 || ctr == 14) {
              // 6: channel has been restarted first time
              // 14: channel has been restarted seconds time

              // If restart counter wasn't reset only 8
              // messages were delivered to destination
              // (because restartMax of channel is set to 1)
              confirm(true); sender ! "%s (%d)".format(event, ctr)
            } else {
              confirm(false)
            }
          }
        }
      }

      val d = system.actorOf(Props(new Destination with Receiver))
      val c = channel(d)

      val respondTo = result[String](c) _

      c ! Deliver

      respondTo(Message("a")) must be("a (6)")
      respondTo(Message("b")) must be("b (14)")
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
        Left (Message(4, sequenceNr = 4L)), // failure #1 at event 4
        Left (Message(4, sequenceNr = 4L)), // failure #2 at event 4, redelivery #1 before restart
        Left (Message(4, sequenceNr = 4L)), // failure #3 at event 4, redelivery #2 before restart
        Left (Message(4, sequenceNr = 4L)), // failure #4 at event 4, redelivery #3 before restart
        Left (Message(4, sequenceNr = 4L)), // failure #5 at event 4, redelivery #1 after restart #1
        Right(Message(4, sequenceNr = 4L)), // success    at event 4, redelivery #2 after restart #1
        Right(Message(5, sequenceNr = 5L)), // success    at event 5
        Right(Message(6, sequenceNr = 6L)), // success    at event 6
        Right(Message(7, sequenceNr = 7L))  // success    at event 7
      )

      List.fill(12)(dq()) must be(expected)

      // send another message to reliable channel
      c ! Message(8, sequenceNr = 0L)

      // check that sequence number is updated appropriately
      dq() must be (Right(Message(8, sequenceNr = 8L)))

    }
    "stop delivery after having reached restartMax and resume on request" in { fixture =>
      import fixture._

      val c = channel(failureDestination(failAtEvent = 1, enqueueFailures = true, failureCount = 8))
      val q = new LinkedBlockingQueue[DeliveryStopped]

      system.eventStream.subscribe(system.actorOf(Props(new Actor {
        def receive = { case event: DeliveryStopped => q add event }
      })), classOf[DeliveryStopped])

      c ! Deliver
      c ! Message(1)

      1 to 8 foreach { _ => dq() must be (Left(Message(1, sequenceNr = 1L))) }

      dequeue(q) // wait for DeliveryStopped event

      c ! Deliver
      dq() must be (Right(Message(1, sequenceNr = 1L)))
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

    val policy = new RedeliveryPolicy(5 seconds, 10 milliseconds, 1, 10 milliseconds, 3)
    def channel(destination: ActorRef) = system.actorOf(Props(new ReliableChannel(1, journal, destination, policy)))


    def dq(queue: LinkedBlockingQueue[Either[Message, Message]]): Either[Message, Message] = super.dequeue(queue) match {
      case Left(msg)  => Left(Message(msg.event, sequenceNr = msg.sequenceNr))
      case Right(msg) => Right(Message(msg.event, sequenceNr = msg.sequenceNr))
    }

    def dq(): Either[Message, Message] = dq(queue)

    def writeOutMsg(msg: Message) {
      val ackSequenceNr: Long = if (msg.ack) msg.sequenceNr else SkipAck
      result[Unit](journal)(WriteOutMsg(1, msg, msg.processorId, ackSequenceNr, responder, false))
    }
  }

  class Destination(queue: Queue[Either[Message, Message]], failAtEvent: Any, var failureCount: Int, enqueueFailures: Boolean) extends Actor { this: Receiver =>
    def receive = {
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
