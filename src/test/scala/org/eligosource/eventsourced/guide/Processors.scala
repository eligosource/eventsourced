package org.eligosource.eventsourced.guide

import java.io.File

import akka.actor._
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.LeveldbJournal

object Processors extends App {
  implicit val system = ActorSystem("example")
  implicit val timeout = Timeout(5 seconds)

  // create a journal
  val journal: ActorRef = LeveldbJournal(new File("target/example-2"))

  // create an event-sourcing extension
  implicit val extension = EventsourcingExtension(system, journal)

  // event-sourced processor definition
  class Processor extends Actor { this: Receiver =>
    def receive = {
      case "foo" => {
        println("received event foo (sequence number = %d)" format message.sequenceNr)
        // make an application-level response (need not be an event message)
        sender ! "processed event foo"
      }
    }
  }

  // create and register event-sourced processor
  val processor: ActorRef = extension.processorOf(Props(new Processor with Receiver with Eventsourced { val id = 1 }))

  // recover registered processors by replaying journaled events
  extension.recover()

  // send event message to processor
  processor ! Message("foo")

  // send event message to processor and receive response
  processor ? Message("foo") onSuccess {
    case resp => println(resp)
  }

  // wait for all messages to arrive (graceful shutdown coming soon)
  Thread.sleep(1000)

  // then shutdown
  system.shutdown()
}