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
package org.eligosource.eventsourced.journal

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

import org.eligosource.eventsourced.core._

class ReplicationSpec extends WordSpec with MustMatchers {
  type FixtureParam = (Fixture, Fixture)

  class Fixture(journalDir: File) {
    implicit val system = ActorSystem("test")
    implicit val timeout = Timeout(5 seconds)

    val dl = system.deadLetters

    val journal = LeveldbJournal(journalDir)
    val replicatingJournal = system.actorOf(Props(new ReplicatingJournal(journal)))

    val queue = new LinkedBlockingQueue[Message]
    val dest = system.actorOf(Props(new Actor {
      def receive = {
        case msg: Message => { queue.put(msg); sender ! Ack }
      }
    }))

    def component(reliable: Boolean) = if (reliable) {
      Component(1, replicatingJournal)
        .addReliableOutputChannelToActor("dest", dest)
        .setProcessor { outputChannels =>
        system.actorOf(Props(new ReplicatedProcessor(outputChannels)))
      }
    } else {
      Component(1, replicatingJournal)
        .addDefaultOutputChannelToActor("dest", dest)
        .setProcessor { outputChannels =>
        system.actorOf(Props(new ReplicatedProcessor(outputChannels)))
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

    class ReplicatedProcessor(outputChannels: Map[String, ActorRef]) extends Actor {
      var ctr = 1

      def receive = {
        case msg: Message => { outputChannels("dest") ! msg.copy(event = ctr); ctr = ctr + 1 }
      }
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

  import Replicator._

  "A slave component with reliable output channels" must {
    "recover from partially replicated output messages and acknowledgements" in { pair =>
      val slaveFixture = pair._2
      val slaveComponent = slaveFixture.component(true)

      import slaveFixture._

      val replicator = system.actorOf(Props(new Replicator(slaveFixture.replicatingJournal, 10)))
      def replicate(cmd: Any) = Await.result(replicator ? cmd, timeout.duration)

      replicator ! RegisterComponents(slaveComponent)

      // all input messages are replicated
      1 to 20 foreach { i =>
        replicate(WriteMsg(1, 0, Message(i, sequenceNr = i), None, dl, false))
      }

      // only out messages and acks 1 - 14 are replicated
      1 to 14 foreach { i =>
        replicate(WriteMsg(1, 1, Message(i, sequenceNr = 20 + i), Some(i), dl, false))
      }

      // out message deletions except 4, 7, 12, 14 are replicated
      1 to 14 filterNot (Set(4, 7, 12, 14).contains) foreach { i =>
        replicate(DeleteMsg(1, 1, 20 + i))
      }

      Replicator.complete(replicator, 5 seconds)
      slaveComponent.inputChannel ! Message(7000)

      var received = List.empty[Message]

      do {
        received = slaveFixture.dequeue() :: received
      } while (received.head.event != 21)

      // replay starts from message 11 (buffer limit of replicator) but reliable
      // output channel additionally causes redelivery of messages 4 and 7.
      received.reverse.map(_.event) must be (List(4, 7, 12, 14, 15, 16, 17, 18, 19, 20, 21))

      // test for increasing sequence numbers (gaps are allowed)
      received.reverse.foldLeft(0L) { (a, m) =>
        m.sequenceNr match { case num => { a must be < (num); num } }
      }
    }
  }
  "A slave component with default output channels" must {
    "recover from partially replicated acknowledgements" in { pair =>
      val slaveFixture = pair._2
      val slaveComponent = slaveFixture.component(false)

      import slaveFixture._

      val replicator = system.actorOf(Props(new Replicator(slaveFixture.replicatingJournal, 10)))
      def replicate(cmd: Any) = Await.result(replicator ? cmd, timeout.duration)

      replicator ! RegisterComponents(slaveComponent)

      // all input messages are replicated
      1 to 20 foreach { i =>
        replicate(WriteMsg(1, 0, Message(i, sequenceNr = i), None, dl, false))
      }

      // acknowledgements except 4, 7, 12, 14 are replicated
      1 to 14 filterNot (Set(4, 7, 12, 14).contains) foreach { i =>
        replicate(WriteAck(1, 1, i))
      }

      Replicator.complete(replicator, 5 seconds)
      slaveComponent.inputChannel ! Message(7000)

      var received = List.empty[Message]

      do {
        received = slaveFixture.dequeue() :: received
      } while (received.head.event != 21)

      // replay starts from message 11 (buffer limit of replicator) but
      // default output channel cannot redeliver messages 4 and 7.
      received.reverse.map(_.event) must be (List(12, 14, 15, 16, 17, 18, 19, 20, 21))

      // test for increasing sequence numbers (gaps are allowed)
      received.reverse.foldLeft(0L) { (a, m) =>
        m.sequenceNr match { case num => { a must be < (num); num } }
      }
    }
  }
  "A master component with reliable output channels" must {
    "be able to fail over to a slave component" in { pair =>
      failover(pair._1, pair._2, true)
    }
  }
  "A master component with default output channels" must {
    "be able to fail over to a slave component" in { pair =>
      failover(pair._1, pair._2, false)
    }
  }

  def failover(masterFixture: Fixture, slaveFixture: Fixture, reliable: Boolean) {
    val masterComponent = masterFixture.component(reliable)
    val slaveComponent = slaveFixture.component(reliable)

    // Create a replicator. This is usually a remote actor created
    // on a slave node and used on the master node. In this test,
    // master and slave are co-located
    val replicator = slaveFixture.system.actorOf(Props(new Replicator(slaveFixture.replicatingJournal, 10)))

    // Replicator event-sources slave component/composite with
    // replicated messages
    replicator ! RegisterComponents(slaveComponent)

    // Configure replicating journal with replicator.
    masterFixture.replicatingJournal ! SetReplicator(Some(replicator))

    // ---------
    // On master
    // ---------

    // Initialize output channels on master
    Composite.init(masterComponent)

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
    slaveComponent.inputChannel ! Message(7000)

    var messages = List.empty[Message]

    do {
      messages = slaveFixture.dequeue() :: messages
    } while (messages.head.event != 21)

    // test for increasing event numbers (gaps are allowed)
    messages.reverse.foldLeft(0) { (a, m) =>
      m.event match { case num: Int => { a must be < (num); num } }
    }

    // test for increasing sequence numbers (gaps are allowed)
    messages.reverse.foldLeft(0L) { (a, m) =>
      m.sequenceNr match { case num => { a must be < (num); num } }
    }
  }
}
