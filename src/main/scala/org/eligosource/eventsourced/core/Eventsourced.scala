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

/**
 * Stackable modification for making an actor persitent via event-sourcing (or command-sourcing).
 * `Eventsourced` inherits all capabilities from [[org.eligosource.eventsourced.core.Emitter]],
 * [[org.eligosource.eventsourced.core.Receiver]] and [[akka.actor.ReceiverBehavior]]. Additionally,
 * it writes any input [[org.eligosource.eventsourced.core.Message]] to a journal. Input messages
 * of any other type are not journaled. Example:
 *
 * {{{
 * import java.io.File
 *
 * import akka.actor._
 * import akka.pattern.ask
 * import akka.util.duration._
 * import akka.util.Timeout
 *
 * import org.eligosource.eventsourced.core._
 * import org.eligosource.eventsourced.journal.LeveldbJournal
 *
 * implicit val system = ActorSystem("example")
 * implicit val timeout = Timeout(5 seconds)
 *
 * // create a journal
 * val journal = LeveldbJournal(new File("target/example"))
 *
 * // Modification 'with Eventsourced' makes actor persistent (using event/command-sourcing)
 * def processorA = new ActorA with Eventsourced
 *
 * // Modification 'with Eventsourced' makes actor persistent (unsing event/command-sourcing)
 * def processorB = new ActorB with Eventsourced
 *
 * // Modification 'with Receiver' makes actor an acknowledging event/command message receiver
 * def destination = new Destination with Receiver
 *
 * // Put actors into an event/command-sourcing context and wire them via channels
 * implicit val context = Context(journal)
 *   .addProcessor(1, processorA)         // add processorA with id == 1
 *   .addProcessor(2, processorB)         // add processorB with id == 2
 *   .addChannel("channelA", 1)           // channel to processorA
 *   .addChannel("channelB", destination) // channel to destination
 *
 * // Recover context from existing journal entries (if any)
 * // - initializes processors
 * // - replays events/commands to processors
 * // - delivers messages via channels that
 * //   haven't been acknowledged so far
 * context.init()
 *
 * // get processor with id == 2
 * val p: ActorRef = context.processors(2)
 *
 * // send event message to p
 * p ! Message("some event")
 *
 * // send event message to p and receive response (Ack) once the event has been persisted
 * p ? Message("some event") onSuccess { case Ack => println("event written to journal") }
 *
 * // send event message to p but receive application-level response from processor
 * p ?? Message("some event") onSuccess { case resp => println("received response %s" format resp) }
 *
 * // send message to p (bypasses journaling because it's not an instance of Message)
 * p ! "blah"
 *
 *
 * class ActorA extends Actor {
 *   def receive = {
 *     case event => {
 *       // do something with event
 *       println("received event = %s" format event)
 *     }
 *   }
 * }
 *
 * class ActorB extends Actor { this: Eventsourced =>
 *   def receive = {
 *     case "blah" => {
 *       println("received non-journaled message")
 *     }
 *     case event => {
 *       // Eventsourced actors have access to:
 *       val seqnr = sequenceNr       // sequence number of journaled message
 *       val sdrid = senderMessageId  // message id provided by sender (duplicate detection)
 *       val initr = initiator        // initiatal sender of message (can be different from current 'sender')
 *       val prcid = id               // processor id
 *       // ...
 *
 *       // do something with event
 *       println("received event = %s (processor id = %d, sequence nr = %d)" format(event, prcid, seqnr))
 *
 *       // Eventsourced actors can emit events to named channels
 *       emitter("channelA").emitEvent("out-a")
 *       emitter("channelB").emitEvent("out-b")
 *
 *       // optionally respond to initial sender (initiator)
 *       // (intitiator == context.system.deadLetters if unknown)
 *       initiator ! "done"
 *     }
 *   }
 * }
 *
 * // Receiver extracts event from received Message and (automatically) acknowledges receipt
 * class Destination extends Actor { this: Receiver =>
 *   def receive = {
 *     case event => {
 *       // Receiver actors have access to:
 *       val seqnr = sequenceNr       // sequence number of journaled message
 *       val sdrid = senderMessageId  // message id provided by sender (duplicate detection)
 *       val initr = initiator        // initiatal sender of message (can be different from current 'sender')
 *       // ...
 *
 *        // do something with event
 *       println("received event = %s" format event)
 *     }
 *   }
 * }
 * }}}
 */
trait Eventsourced extends Emitter {
  private var _id: Int = _

  /**
   * Processor id. Defined by application when adding a processor to a
   * [[org.eligosource.eventsourced.core.Context]].
   */
  def id = _id

  abstract override def receive = {
    case SetId(id) => {
      _id = id
    }
    case cmd: SetContext => {
      super.receive(cmd)
    }
    case msg: Message if (sender == journal) => {
      super.receive(msg.copy(processorId = id))
    }
    case msg: Message /* received from channel or any other sender */ => {
      journal forward WriteInMsg(id, msg, self)
    }
    case msg /* any other message type bypasses journaling */ => {
      super.receive(msg)
    }
  }
}
