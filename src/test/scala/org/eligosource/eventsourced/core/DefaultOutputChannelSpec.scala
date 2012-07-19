package org.eligosource.eventsourced.core

import java.io.File
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}

import akka.actor._

import org.apache.commons.io.FileUtils

import org.scalatest._
import org.scalatest.matchers.MustMatchers

class DefaultOutputChannelSpec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  implicit val system = ActorSystem("test")

  val journalDir = new File("target/journal")

  override protected def beforeEach() {
    FileUtils.deleteDirectory(journalDir)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  import Channel._

  def fixture = new {
    val queue = new LinkedBlockingQueue[Message]

    val destination =
      system.actorOf(Props(new DefaultOutputChannelTestDesination(queue)))
    val journaler =
      system.actorOf(Props(new Journaler(journalDir)))
    val channel =
      system.actorOf(Props(new DefaultOutputChannel(0, 1, journaler)))

    channel ! SetDestination(destination)

    def dequeue(timeout: Long = 5000): Message = {
      queue.poll(timeout, TimeUnit.MILLISECONDS)
    }
  }

  "A default output channel" must {
    "buffer messages before initial delivery" in {
        val f = fixture; import f._

        channel ! Message("a", None, None, 0L)
        channel ! Message("b", None, None, 0L)

        channel ! Deliver

        dequeue() must be (Message("a", None, None, 1L))
        dequeue() must be (Message("b", None, None, 2L))
    }
    "not buffer messages after initial delivery" in {
      val f = fixture; import f._

      channel ! Message("a", None, None, 0L)

      channel ! Deliver

      channel ! Message("b", None, None, 0L)
      channel ! Message("c", None, None, 0L)

      dequeue() must be (Message("a", None, None, 1L))
      dequeue() must be (Message("b", None, None, 2L))
      dequeue() must be (Message("c", None, None, 3L))
    }
  }
}

class DefaultOutputChannelTestDesination(blockingQueue: LinkedBlockingQueue[Message]) extends Actor {

  def receive = {
    case msg: Message => blockingQueue.put(msg)
  }
}