package org.eligosource.eventsourced.core

import java.io.File
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest._
import org.scalatest.matchers.MustMatchers

class ReliableOutputChannelSpec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  implicit val system = ActorSystem("test")
  implicit val timeout = Timeout(5 seconds)

  val journalDir = new File("target/journal")


  override protected def beforeEach() {
    FileUtils.deleteDirectory(journalDir)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  import Channel._
  import Journaler._
  import Message._

  def fixture = new {
    val queue = new LinkedBlockingQueue[Message]

    val successDestination =
      system.actorOf(Props(new ReliableOutputChannelTestDesination(queue)))
    def failureDestination(enqueueFailures: Boolean, failureCount: Int) =
      system.actorOf(Props(new ReliableOutputChannelTestDesination(queue, enqueueFailures, failureCount)))

    lazy val journaler = system.actorOf(Props(new Journaler(journalDir)))
    lazy val channel = {
      val result = system.actorOf(Props(new ReliableOutputChannel(0, 1, journaler, 50 milliseconds)))
      result ! SetDestination(successDestination)
      result
    }

    def writeOutputMessage(msg: Message) {
      Await.result(journaler ? WriteMsg(Key(0, 1, msg.sequenceNr, 0), msg), timeout.duration)
    }

    def dequeueOutputMessage(timeout: Long = 5000): Message = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }
  }

  "A reliable output channel" when {
    "just created" must {
      "derive its counter value from stored output messages" in {
        val f = fixture; import f._
        writeOutputMessage(Message("x", None, None, 7L))

        channel ! Message("y", None, None, 0L)
        channel ! Message("z", None, None, 0L)

        dequeueOutputMessage() must be (Message("y", None, None, 8L))
        dequeueOutputMessage() must be (Message("z", None, None, 9L))
      }
      "deliver stored output messaged on request" in {
        val f = fixture; import f._
        writeOutputMessage(Message("a", None, None, 3L))
        writeOutputMessage(Message("b", None, None, 4L))

        channel ! Deliver
        channel ! Message("c", None, None, 0L)

        dequeueOutputMessage() must be (Message("a", None, None, 3L))
        dequeueOutputMessage() must be (Message("b", None, None, 4L))
        dequeueOutputMessage() must be (Message("c", None, None, 5L))
      }
    }
    "delivering a single output message" must {
      "recover from destination failures" in {
        val f = fixture; import f._

        channel ! SetDestination(failureDestination(true, 2))
        channel ! Message("a", None, None, 0L)

        dequeueOutputMessage() must be (Message("a", None, None, 1L))
        dequeueOutputMessage() must be (Message("a", None, None, 1L)) // redelivery 1
        dequeueOutputMessage() must be (Message("a", None, None, 1L)) // redelivery 2
      }
    }
    "delivering multiple output messages" must {
      "recover from destination failures" in {
        val f = fixture; import f._

        channel ! SetDestination(failureDestination(false, 2))

        // first two messages will fail
        1 to 4 foreach { i => channel ! Message(i, None, None, 0L) }

        val msgs = List(
          dequeueOutputMessage(),
          dequeueOutputMessage(),
          dequeueOutputMessage(),
          dequeueOutputMessage(),
          dequeueOutputMessage(100),
          dequeueOutputMessage(100))

          if (msgs(4) != null ||
              msgs(5) != null) {
            println("-------------------------------------")
            println("recovery caused possible duplicate(s)")
            println("-------------------------------------")
          }

          // the following assertions show the most likely order
          // of messages (as they arrive at the destination)
          msgs must contain(Message(3, None, None, 3L))
          msgs must contain(Message(4, None, None, 4L))
          msgs must contain(Message(1, None, None, 1L))
          msgs must contain(Message(2, None, None, 2L))
          // (messages can get out of order during a delivery failure but
          // clients can re-order them based on the message sequence number)
      }
    }
  }
}

class ReliableOutputChannelTestDesination(
    // for interaction with test code
    blockingQueue: LinkedBlockingQueue[Message],
    // if failing messages should be added to queue
    enqueueFailures: Boolean = false,
    // number of messages that will fail
    var failureCount: Int = 0) extends Actor {

  def receive = {
    case msg: Message => {

      if (failureCount > 0) {
        failureCount = failureCount - 1
        sender ! Status.Failure(new Exception("test"))
        if (enqueueFailures) blockingQueue.put(msg)
      } else {
        sender ! ()
        blockingQueue.put(msg)
      }
    }
  }
}