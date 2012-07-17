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
    def failureDestination(failureCount: Int) =
      system.actorOf(Props(new ReliableOutputChannelTestDesination(queue, failureCount)))

    lazy val journaler = system.actorOf(Props(new Journaler(journalDir)))
    lazy val channel = {
      val result = system.actorOf(Props(new ReliableOutputChannel(0, 1, journaler, 100 milliseconds)))
      result ! SetDestination(successDestination)
      result
    }

    def writeOutputMessage(msg: Message) {
      Await.result(journaler ? WriteMsg(Key(0, 1, msg.sequenceNr, 0), msg), timeout.duration)
    }

    def dequeueOutputMessage(): Message = {
      queue.poll(5, TimeUnit.SECONDS)
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
    "delivering output messages" must {
      "recover from destination failures" in {
        val f = fixture; import f._

        channel ! SetDestination(failureDestination(2))
        channel ! Message("a", None, None, 0L)

        dequeueOutputMessage() must be (Message("a", None, None, 1L))
        dequeueOutputMessage() must be (Message("a", None, None, 1L)) // redelivery 1
        dequeueOutputMessage() must be (Message("a", None, None, 1L)) // redelivery 2
      }
    }
  }
}

class ReliableOutputChannelTestDesination(blockingQueue: LinkedBlockingQueue[Message], var failureCount: Int = 0) extends Actor {
  def receive = {
    case msg: Message => {
      blockingQueue.put(msg)
      if (failureCount > 0) {
        sender ! Status.Failure(new Exception("test-%d" format failureCount))
        failureCount = failureCount - 1
      } else {
        sender ! ()
      }
    }
  }
}