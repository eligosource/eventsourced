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
package org.eligosource.eventsourced.journal.common

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.existentials

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.core.Eventsourced.CompleteProcessing

abstract class ReplaySpec extends WordSpec with MustMatchers {
  type FixtureParam = Fixture

  val Increment = "increment"
  val GetSnapshotOffer = "getSo"
  val GetMessages = "getMs"

  implicit val duration = 10 seconds
  implicit val timeout = Timeout(duration)

  case class WaitFor(num: Int)

  class Processor extends Actor { this: Receiver =>
    var counter = 0

    def receive = {
      case sr : SnapshotRequest => {
        Thread.sleep(10) // ensure that snapshots have different timestamps
        sr.process(counter)
      }
      case so @ SnapshotOffer(Snapshot(_, _, _, ctr: Int)) => {
        counter = ctr
      }
      case Increment => {
        counter += 1
      }
    }
  }

  class Target(val system: ActorSystem) {
    // This actor doesn't journal messages. It is Eventsourced only for
    // - responding to CompleteProcessing
    // - unwrapping Replayed messages
    val actor = system.actorOf(Props(new TargetActor with Eventsourced { val id = 3 } ))
    def awaitReplayProcessed = Await.result(actor ? CompleteProcessing, duration)
    def receivedOffer = request[Option[SnapshotOffer]](GetSnapshotOffer)
    def receivedMessages = request[List[Message]](GetMessages)
    private def request[A : scala.reflect.ClassTag](r: Any): A = Await.result((actor ? r).mapTo[A], duration)
  }

  class TargetActor extends Actor {
    var oo: Option[SnapshotOffer] = None
    var ms: List[Message] = Nil

    def receive = {
      case o: SnapshotOffer    => { oo = Some(o) }
      case m: Message          => { ms = m :: ms }
      case GetSnapshotOffer    => sender ! oo
      case GetMessages         => sender ! ms.reverse
    }
  }

  class Fixture {
    implicit val system = ActorSystem("test")

    val journal = journalProps.createJournal
    val extension = EventsourcingExtension(system, journal)
    val target = new Target(system)

    val processor1 = extension.processorOf(Props(new Processor with Receiver with Eventsourced { val id = 1 }))
    val processor2 = extension.processorOf(Props(new Processor with Receiver with Eventsourced { val id = 2 }))

    import system.dispatcher

    processor1 ! Message(Increment)
    processor1 ! Message(Increment)
    val f11 = (processor1 ? SnapshotRequest).mapTo[SnapshotSaved]
    processor1 ! Message(Increment)
    processor1 ! Message(Increment)
    val f12 = (processor1 ? SnapshotRequest).mapTo[SnapshotSaved]
    processor1 ! Message(Increment)

    // wait for processor1 messages being processed
    extension.completeProcessing(Set(1))

    // wait for processor1 snapshot being saved
    val List(ss11, ss12) = Await.result(Future.sequence(Seq(f11, f12)), duration)

    processor2 ! Message(Increment)
    val f21 = (processor2 ? SnapshotRequest).mapTo[SnapshotSaved]
    processor2 ! Message(Increment)
    val f22 = (processor2 ? SnapshotRequest).mapTo[SnapshotSaved]

    // wait for processor2 messages being processed
    extension.completeProcessing(Set(2))

    // wait for processor2 snapshot being saved
    val List(ss21, ss22) = Await.result(Future.sequence(Seq(f21, f22)), duration)

    def shutdown() {
      system.shutdown()
      system.awaitTermination(duration)
    }
  }

  def withFixture(test: OneArgTest) {
    val fixture = new Fixture
    try { test(fixture) } finally { fixture.shutdown() }
  }

  def journalProps: JournalProps

  "A journal" when {
    "used for snapshotted replay" must {
      "replay to current state from latest snapshot" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, true), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 4, ss12.timestamp, 4))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay to current state from selected snapshot (sequence number criteria include latest snapshot)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr < 10), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 4, ss12.timestamp, 4))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay to current state from selected snapshot (timestamp criteria include latest snapshot)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.timestamp <= System.currentTimeMillis), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 4, ss12.timestamp, 4))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay to current state from selected snapshot (sequence number criteria don't include latest snapshot)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr < 4), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1),
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay to current state from selected snapshot (timestamp criteria don't include latest snapshot)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.timestamp < ss12.timestamp), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1),
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay to upper limit from selected snapshot (sequence number criteria don't include latest snapshot)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr < 4, 4L), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1)
        ))
      }
      "replay to upper limit from selected snapshot (timestamp criteria don't include latest snapshot)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.timestamp < ss12.timestamp, 4), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1)
        ))
      }
      "replay to upper limit from selected snapshot (upper limit tightens selction criteria)" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr < 20, 3L), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 3, 0L, 1)
        ))
      }
      "only offer snapshot if snapshot represents current state" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(2, true), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(2, 7, ss22.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(Nil)
      }
      "only offer snapshot if upper limit excludes messages after snapshot" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, true, 2L), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
        target.receivedMessages.map(_.withTimestamp(0L)) must be(Nil)
      }
    }
    "used for non-snapshotted replay" must {
      "replay all messages" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer must be(None)
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 1, 0L, 1),
          Message(Increment, 2, 0L, 1),
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1),
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay all messages if no snapshot could be selected" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr > 20), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer must be(None)
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 1, 0L, 1),
          Message(Increment, 2, 0L, 1),
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1),
          Message(Increment, 5, 0L, 1)
        ))
      }
      "replay to upper limit" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, 0, 4), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer must be(None)
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 1, 0L, 1),
          Message(Increment, 2, 0L, 1),
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1)
        ))
      }
      "replay to upper limit if no snapshot could be selected" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr > 20, 4), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer must be(None)
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 1, 0L, 1),
          Message(Increment, 2, 0L, 1),
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1)
        ))
      }
      "replay from lower limit to upper limit" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, 3, 4), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer must be(None)
        target.receivedMessages.map(_.withTimestamp(0L)) must be(List(
          Message(Increment, 3, 0L, 1),
          Message(Increment, 4, 0L, 1)
        ))
      }
      "not replay for lower limit > upper limit" in { fixture =>
        import fixture._

        journal ! ReplayInMsgs(ReplayParams(1, 5, 4), target.actor)

        target.awaitReplayProcessed
        target.receivedOffer must be(None)
        target.receivedMessages.map(_.withTimestamp(0L)) must be(Nil)
      }
    }
  }
}

abstract class PersistentReplaySpec extends ReplaySpec {
  "recognize stored snapshots when started" in { fixture =>
    import fixture._

    system.shutdown()
    system.awaitTermination(duration)

    val anotherSystem = akka.actor.ActorSystem("test")
    val anotherJournal = journalProps.createJournal(anotherSystem)
    val anotherExtension = EventsourcingExtension(anotherSystem, anotherJournal)
    val anotherTarget1 = new Target(anotherSystem)
    val anotherTarget2 = new Target(anotherSystem)

    anotherJournal ! ReplayInMsgs(ReplayParams(1, true), anotherTarget1.actor)
    anotherJournal ! ReplayInMsgs(ReplayParams(1, _.sequenceNr < 4), anotherTarget2.actor)

    anotherTarget1.awaitReplayProcessed
    anotherTarget1.receivedOffer.get.snapshot must be(Snapshot(1, 4, ss12.timestamp, 4))
    anotherTarget1.receivedMessages.map(_.withTimestamp(0L)) must be(List(
      Message(Increment, 5, 0L, 1)
    ))

    anotherTarget2.awaitReplayProcessed
    anotherTarget2.receivedOffer.get.snapshot must be(Snapshot(1, 2, ss11.timestamp, 2))
    anotherTarget2.receivedMessages.map(_.withTimestamp(0L)) must be(List(
      Message(Increment, 3, 0L, 1),
      Message(Increment, 4, 0L, 1),
      Message(Increment, 5, 0L, 1)
    ))

    anotherSystem.shutdown()
    anotherSystem.awaitTermination(duration)
  }
}
