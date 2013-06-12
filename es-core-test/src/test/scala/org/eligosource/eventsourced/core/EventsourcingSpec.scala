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

import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.journal.common.JournalProps
import org.eligosource.eventsourced.journal.leveldb._

abstract class EventsourcingSpec[T <: EventsourcingFixture[_] : ClassTag] extends WordSpec with MustMatchers {
  type FixtureParam = T

  def createFixture =
    implicitly[ClassTag[T]].runtimeClass.newInstance().asInstanceOf[T]

  def withFixture(test: OneArgTest) {
    val fixture = createFixture
    try {
      test(fixture)
    } finally {
      fixture.shutdown()
    }
  }
}

trait EventsourcingFixtureOps[A] { self: EventsourcingFixture[A] =>
  val queue = new LinkedBlockingQueue[A]

  def cleanup()
  def journalProps: JournalProps

  def dequeue[A](queue: LinkedBlockingQueue[A]): A = {
    queue.poll(timeout.duration.toMillis, TimeUnit.MILLISECONDS)
  }

  def dequeue(): A = {
    dequeue(queue)
  }

  def dequeue(p: A => Unit) {
    p(dequeue())
  }

  def result[A : ClassTag](actor: ActorRef)(r: Any): A = {
    Await.result(actor.ask(r)(timeout).mapTo[A], timeout.duration)
  }
}

class EventsourcingFixture[A] extends EventsourcingFixtureOps[A] with LeveldbSupport {
  implicit val timeout = Timeout(10 seconds)
  implicit val system = ActorSystem("test")

  val journal = journalProps.createJournal
  val extension = EventsourcingExtension(system, journal)

  def shutdown() {
    system.shutdown()
    system.awaitTermination(timeout.duration)
    cleanup()
  }
}

trait FutureCommands {
  def await()
}

class CommandListener(latch: CountDownLatch, predicate: PartialFunction[Any, Boolean]) extends Actor {
  def receive = {
    case msg => if (predicate.isDefinedAt(msg) && predicate(msg)) latch.countDown()
  }
}

object CommandListener {
  def apply(journal: ActorRef, count: Int)(predicate: PartialFunction[Any, Boolean])(implicit system: ActorSystem): FutureCommands = {
    val latch = new CountDownLatch(count)
    journal ! JournalProtocol.SetCommandListener(Some(system.actorOf(Props(new CommandListener(latch, predicate)))))
    new FutureCommands {
      def await() = latch.await(5, TimeUnit.SECONDS)
    }
  }
}
