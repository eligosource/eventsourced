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
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}

import akka.actor._
import akka.util.duration._

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

class DefaultOutputChannelSpec extends WordSpec with MustMatchers {
  import Channel._

  type FixtureParam = Fixture

  class Fixture {
    implicit val system = ActorSystem("test")

    val queue = new LinkedBlockingQueue[Message]
    val destination = system.actorOf(Props(new DefaultOutputChannelTestDesination(queue)))

    val journalDir = new File("target/journal")
    val journaler = system.actorOf(Props(new Journaler(journalDir)))
    val channel = system.actorOf(Props(new DefaultOutputChannel(1, 1, journaler)))

    channel ! SetDestination(destination)

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

  "A default output channel" must {
    "buffer messages before initial delivery" in { fixture =>
      import fixture._

      channel ! Message("a")
      channel ! Message("b")

      channel ! Deliver

      dequeue() must be (Message("a"))
      dequeue() must be (Message("b"))
    }
    "not buffer messages after initial delivery" in { fixture =>
      import fixture._

      channel ! Message("a")

      channel ! Deliver

      channel ! Message("b")
      channel ! Message("c")

      dequeue() must be (Message("a"))
      dequeue() must be (Message("b"))
      dequeue() must be (Message("c"))
    }
  }
}

class DefaultOutputChannelTestDesination(blockingQueue: LinkedBlockingQueue[Message]) extends Actor {
  def receive = {
    case msg: Message => blockingQueue.put(msg)
  }
}