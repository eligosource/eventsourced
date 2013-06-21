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
package org.eligosource.eventsourced.example

import java.io.File

import scala.concurrent.duration._
import scala.util._

import akka.actor._
import akka.pattern.ask
import akka.util._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.leveldb._

object SnapshotExample extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  val journalDir: File = new File("target/snapshots")
  val journal: ActorRef = LeveldbJournalProps(journalDir, native = false).createJournal
  val extension = EventsourcingExtension(system, journal)

  case class Increment(by: Int)

  class Processor extends Actor { this: Receiver =>
    var counter = 0

    def receive = {
      case Increment(by) => {
        counter += by
        println(s"incremented counter by ${by} to ${counter} (snr = ${sequenceNr})")
      }
      case sr @ SnapshotRequest(pid, snr, _) => {
        sr.process(counter)
        println(s"processed snapshot request for ctr = ${counter} (snr = ${snr})")

      }
      case so @ SnapshotOffer(Snapshot(_, snr, time, ctr: Int)) => {
        counter = ctr
        println(s"accepted snapshot offer for ctr = ${counter} (snr = ${snr} time = ${time}})")
      }
    }
  }

  import system.dispatcher
  import extension._

  val processor = processorOf(Props(new Processor with Receiver with Eventsourced { val id = 1 } ))

  extension.recover(replayParams.allWithSnapshot)
  processor ! Message(Increment(1))
  processor ! Message(Increment(2))

  (processor ? SnapshotRequest).mapTo[SnapshotSaved].onComplete {
    case Success(SnapshotSaved(pid, snr, time)) => println(s"snapshotting succeeded: pid = ${pid} snr = ${snr} time = ${time}")
    case Failure(e)                             => println(s"snapshotting failed: ${e.getMessage}")
  }

  processor ! Message(Increment(3))

  Thread.sleep(1000)
  system.shutdown()
}
