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
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

class ReplicationSpec extends WordSpec with MustMatchers {
  type FixtureParam = (Fixture, Fixture)

  class Fixture(journalDir: File) {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val journaler = system.actorOf(Props(new Journaler(journalDir)))
    val replicatingJournaler = system.actorOf(Props(new ReplicatingJournaler(journaler)))

    val queue = new LinkedBlockingQueue[Message]
    val dest = system.actorOf(Props(new Actor {
      def receive = {
        case msg: Message => { queue.put(msg); sender ! () }
      }
    }))

    def component(reliable: Boolean) = if (reliable) {
      Component(0, replicatingJournaler)
        .addReliableOutputChannelToActor("dest", dest)
        .setProcessor { outputChannels =>
        system.actorOf(Props(new ReplicatedProcessor(outputChannels, journalDir.getName)))
      }
    } else {
      Component(0, replicatingJournaler)
        .addDefaultOutputChannelToActor("dest", dest)
        .setProcessor { outputChannels =>
        system.actorOf(Props(new ReplicatedProcessor(outputChannels, journalDir.getName)))
      }
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
    val pair = (
      new Fixture(new File("target/journal-master")),
      new Fixture(new File("target/journal-slave"))
    )
    try { test(pair) } finally {
      pair._1.shutdown()
      pair._2.shutdown()
    }
  }

  "A replicated component with reliable output channels" must {
    "be able to fail over" in { pair =>
      failover(pair._1, pair._2, true)
    }
  }
  "A replicated component with default output channels" must {
    "be able to fail over" in { pair =>
      failover(pair._1, pair._2, false)
    }
    "blah" in { pair =>

    }
  }


  def failover(masterFixture: Fixture, slaveFixture: Fixture, reliable: Boolean) {
    import Replicator._

    val masterComponent = masterFixture.component(reliable)
    val slaveComponent = slaveFixture.component(reliable)

    // Create a replicator. This is usually a remote actor created
    // on a slave node and used on the master node. In this test,
    // master and slave are co-located
    val replicator = slaveFixture.system.actorOf(Props(new Replicator(slaveFixture.journaler)))

    // Replicator event-sources slave component/composite with
    // replicated messages
    replicator ! RegisterComponents(slaveComponent)

    // Configure replicating journaler with replicator.
    masterFixture.replicatingJournaler ! SetReplicator(Some(replicator))

    // ---------
    // On master
    // ---------

    // Initialize output channels on master
    Composite.recount(masterComponent)
    Composite.deliver(masterComponent)

    {
      import masterFixture.timeout
      import masterFixture.system

      // submit 20 messages and ...
      val submissions = 1 to 20 map { i => masterComponent.inputChannel.ask(Message(i)) }

      // await journaling and replication
      Await.result(Future.sequence(submissions), timeout.duration)
    }

    // now assume master crashed and there's a failover to slave
    // ...

    // ---------
    // On slave
    // ---------

    // we have received all input messages and an undefined
    // number of ACKs. Non-acknowledged input messages may
    // appear as duplicates during the next step ...

    // replicator completes the failover procedure recovers
    // state and re-delivers non-acknowledged input messages
    Replicator.complete(replicator, 5 seconds)

    // now slave component is the new master and can process
    // new messages. TODO: init replicator on new master
    slaveComponent.inputChannel ! Message(0)

    var messages = List.empty[Message]

    do {
      messages = slaveFixture.dequeue() :: messages
    } while (messages.head.event != 21)

    // TODO: demonstrate that there can be actually gaps
    messages.reverse.foldLeft(0) { (a, m) =>
      // test for increasing event numbers (where gaps are allowed)
      m.event match { case num: Int => { a must be < (num); num } }
    }

    // TODO: demonstrate that there can be gaps in sequence numbers
    messages.reverse.foldLeft(messages.last.sequenceNr - 1L) { (a, m) =>
    // test for increasing sequence numbers (gaps are not allowed)
      m.sequenceNr match { case snr => { a must be(snr - 1); snr } }
    }

    messages.foreach(println)
  }
}

class ReplicatedProcessor(outputChannels: Map[String, ActorRef], name: String) extends Actor {
  var ctr = 1

  def receive = {
    case msg: Message => { outputChannels("dest") ! msg.copy(event = ctr); ctr = ctr + 1 }
  }
}
