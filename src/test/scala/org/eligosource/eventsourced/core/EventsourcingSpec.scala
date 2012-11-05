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
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.apache.commons.io.FileUtils

import org.scalatest.fixture._
import org.scalatest.matchers.MustMatchers

import org.eligosource.eventsourced.journal.LeveldbJournal

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

trait EventsourcingFixture[A] {
  implicit val system = ActorSystem("test")
  implicit val timeout = Timeout(5 seconds)

  val journalDir = new File("target/journal")
  val journal = LeveldbJournal(journalDir)
  val queue = new LinkedBlockingQueue[A]

  val extension = EventsourcingExtension(system, journal)

  def dequeue[A](queue: LinkedBlockingQueue[A]): A = {
    queue.poll(5000, TimeUnit.MILLISECONDS)
  }

  def dequeue(): A = {
    dequeue(queue)
  }

  def dequeue(p: A => Unit) {
    p(dequeue())
  }

  def request(actor: ActorRef)(r: Any): Any = {
    Await.result(actor.ask(r), timeout.duration)
  }

  def shutdown() {
    system.shutdown()
    system.awaitTermination(5 seconds)
    FileUtils.deleteDirectory(journalDir)
  }
}
