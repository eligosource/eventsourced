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
package org.eligosource.eventsourced.journal.common.serialization

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._

class SerializerSpec extends WordSpec with MustMatchers {
  import SerializerSpec._

  type FixtureParam = Fixture

  class Fixture {
    implicit val timeout = Timeout(5 seconds)

    val config = ConfigFactory.load("serializer")
    val configCommon = config.getConfig("common")

    val server = ActorSystem("server", config.getConfig("server").withFallback(configCommon))
    val client = ActorSystem("client", config.getConfig("client").withFallback(configCommon))

    server.actorOf(Props[RemoteActor], "remote")

    Thread.sleep(100)

    def shutdown() {
      server.shutdown()
      client.shutdown()
      server.awaitTermination(5 seconds)
      client.awaitTermination(5 seconds)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  "A MessageSerializer" must {
    "serialize event messages" in { fixture =>
      import fixture._
      import client.dispatcher

      val responseFuture = for {
        ActorIdentity(1, Some(remote)) <- client.actorSelection("akka.tcp://server@127.0.0.1:2652/user/remote") ? Identify(1)
        response <- remote ? Message("a", confirmationTarget = remote, confirmationPrototype = Confirmation(1, 1, 1, true))
      } yield response

      Await.result(responseFuture, timeout.duration) must be(Message("success: a"))
    }
  }

  "A ConfirmationSerializer" must {
    "serialize confirmation messages" in { fixture =>
      import fixture._
      import client.dispatcher

      val responseFuture = for {
        ActorIdentity(1, Some(remote)) <- client.actorSelection("akka.tcp://server@127.0.0.1:2652/user/remote") ? Identify(1)
        response <- remote ? Confirmation(1, 1, 1, true)
      } yield response

      Await.result(responseFuture, timeout.duration) must be(Confirmation(2, 2, 2, false))
    }
  }
}

object SerializerSpec {
  class RemoteActor extends Actor {
    def receive = {
      case msg: Message => {
        if (msg.confirmationTarget == self && msg.confirmationPrototype == Confirmation(1, 1, 1, true))
          sender ! Message("success: %s" format msg.event)
        else
          sender ! Message("failure: %s" format msg.event)
      }
      case Confirmation(1, 1, 1, true) => {
        sender ! Confirmation(2, 2, 2, false)
      }
    }
  }
}