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

import akka.actor._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps

import org.eligosource.eventsourced.patterns.reliable.requestreply._

object ReliableRequestReplyExample extends App {
  /*
    Configuration
    -------------

    Processor --> reliable request-reply channel --> Destination
       ^                                                 |
       |                                                 |
       --------------------------------------------------

    In this example the Destination is local, but usually it is a remote
    actor accessed over an unreliable network. A Processor sends requests
    to a Destination and the Destination eventually replies.

    Properties
    ----------

    - Processor persists request and reply messages (Eventsourced processor)
      so that any state derived from past communications can be recovered
      during replay.
    - Request-reply is re-tried on destination failures (no response, failure
      response, network problems ...) but also during recovery after processor
      failures (JVM crashes, ...).
    - Processor will either receive a reply from Destination or a failure
      message (DestinationNotAvailable or DestinationFailure) from the
      reliable request-reply channel.
    - Delivery guarantees are at-least-once for both requests to Destination
      as well as replies to Processor.
    - Delivery confirmations (acknowledgements) are done by application logic.
      A request-reply is considered as complete when Processor positively
      confirms the receipt of the reply. Otherwise it is re-tried (either
      immediately or during recovery after a crash).
    - Reliable request-reply also can be used to deal with 'external updates'
      and 'external queries' as described by Martin Fowler
      * http://martinfowler.com/eaaDev/EventSourcing.html#ExternalUpdates
      * http://martinfowler.com/eaaDev/EventSourcing.html#ExternalQueries
    - Reliable request-reply channels can also be used by an event-sourced
      'error kernel' for communication with collaborators that may fail. In
      this example, the Processor is the error kernel and the channel is a
      child actor executing operations that may fail.
  */

  implicit val system = ActorSystem("patterns")

  val journal: ActorRef = Journal(LeveldbJournalProps(new File("target/patterns")))
  val extension = EventsourcingExtension(system, journal)

  val destination = system.actorOf(Props(new Destination with Receiver))
  val processor = extension.processorOf(Props(new Processor(destination) with ProcessorIdempotency with Emitter with Eventsourced), name = Some("processor"))

  extension.recover()
  processor ! Message(Req("hello", System.currentTimeMillis()))

  Thread.sleep(5000)
  system.shutdown()

  case class Req(msg: String, id: Long) // request
  case class Rep(msg: String, id: Long) // reply

  /**
   * Eventsourced processor that communicates with `Destination` via
   * a reliable request reply channel.
   */
  class Processor(destination: ActorRef) extends Actor { this: Emitter =>
    val id = 1

    // processor state
    var numReplies = 0

    // Create and register a reliable request-reply channel
    EventsourcingExtension(context.system).channelOf(ReliableRequestReplyChannelProps(1, destination)
      .withRedeliveryMax(3)
      .withRedeliveryDelay(0 seconds)
      .withRestartMax(1)
      .withRestartDelay(0 seconds)
      .withConfirmationTimeout(2 seconds)
      .withReplyTimeout(1 second))(context)

    def receive = {
      case r @ Req(msg, id) => {
        // emit request message to destination via reliable
        // request-reply channel
        emitter(1) sendEvent r.copy(id = message.sequenceNr)
        println("request: %s" format r)
      }
      case r @ Rep(msg, id) => {
        // update state
        numReplies += 1
        // positively confirm delivery of reply (so that channel
        // can deliver the next message)
        confirm(true)
        println("reply: %s" format r)
      }
      case DestinationNotResponding(channelId, failureCount, request) => {
        // retry according to redelivery policy or escalate after
        // last redelivery attempt (requires a DeliveryStopped
        // listener on event stream which is not shown here ...)
        confirm(false)
        println("destination of channel %d not responding (%d)" format (channelId, failureCount))
      }
      case DestinationFailure(channelId, failureCount, Req(msg, id), throwable) => {
        // redeliver (i.e. retry) 3 times and proceed with next message
        // (queued by reliable channel) if failure persists
        confirm(failureCount > 2)
        println("destination of channel %d failed (%d)" format (channelId, failureCount))
      }
    }
  }

  trait ProcessorIdempotency extends Actor { this: Receiver =>
    var lastReplyId = 0L
    // - only detect reply duplicates and ignore failure duplicates
    //   (i.e. DestinationNotResponding and DestinationFailure)
    abstract override def receive = {
      case r @ Rep(_, id) => if (id <= lastReplyId) {
        println("reply duplicate: %s" format r)
        confirm(true)
      } else {
        lastReplyId = id
        super.receive(r)
      }
      case msg => super.receive(msg)
    }
  }

  class Destination extends Actor {
    var reply = false
    def receive = {
      case Req(msg, id) => {
        // only reply to every seconds request
        if (reply) sender ! Rep("re: %s".format(msg), id)
        reply = !reply
        // duplicate detection based on application-specific
        // request id not shown ...
      }
    }
  }
}