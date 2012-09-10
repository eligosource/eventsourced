package org.eligosource.eventsourced.journal

import collection.immutable.Queue

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.duration._
import akka.util.{Duration, Timeout}

import org.eligosource.eventsourced.core._

class ReplicatingJournal(journal: ActorRef) extends Actor {
  import Replicator._

  implicit val executor = context.dispatcher

  var messageCounter = 1L
  var deliveryCounter = 1L

  var replicator: Option[ActorRef] = None
  var sequencer: ActorRef = _

  val success = Promise.successful(())

  def receive = {
    case SetReplicator(r) => {
      replicator = r
    }
    case cmd: ReplayInput => {
      journal forward cmd
    }
    case cmd: ReplayOutput => {
      journal forward cmd
    }
    case cmd: WriteMsg => {
      val d = deliveryCounter
      val c = if(cmd.genSequenceNr) cmd.forSequenceNr(messageCounter) else cmd
      execute(c.copy(target = context.system.deadLetters)) {
        sequencer ! (d, (c.target, c.message))
      }
      deliveryCounter = deliveryCounter + 1
      messageCounter = c.message.sequenceNr + 1
    }
    case cmd => {
      execute(cmd)(())
    }
  }

  def execute(cmd: Any)(onSuccess: => Unit) {
    val jf = journal.ask(cmd)(journalTimeout)
    val rf = if (replicator.isDefined) replicator.get.ask(cmd)(replicatorTimeout) else success
    val cf = Future.sequence(List(jf, rf))

    val s = sender

    cf onSuccess {
      case r => { s ! Ack; onSuccess }
    }

    cf onFailure {
      case e => {
        val jfSuccess = jf.value.map(_.isRight).getOrElse(false)
        val rfSuccess = rf.value.map(_.isRight).getOrElse(false)

        (jfSuccess, rfSuccess) match {
          case (true, false) => {
            // continue at risk without replication
            self ! SetReplicator(None)
            // inform sender about success
            s ! Ack
            // ...
            // TODO: inform cluster manager to re-attach slave
          }
          case other => {
            s ! Status.Failure(e)
          }
        }
      }
    }
  }

  def getCounter: Long = {
    val future = journal.ask(GetCounter)(counterInitTimeout)
    Await.result(future.mapTo[Long], counterInitTimeout.duration)
  }

  override def preStart() {
    // initialize message counter from stored sequence number
    messageCounter = getCounter

    // use a delivery counter for re-sequencing future callbacks
    val deliveryCounter = this.deliveryCounter
    sequencer = context.actorOf(Props(new Sequencer {
      delivered = deliveryCounter - 1L
      def receiveSequenced = {
        case (target: ActorRef, message: Message) => {
          if (target != context.system.deadLetters) target ! message
        }
      }
    }))
  }
}

class Replicator(journal: ActorRef, inputBufferLimit: Int = 100) extends Actor {
  import Replicator._

  var components = Map.empty[Int, Component]

  /**
   * EXPERIMENTAL
   *
   * Use an input buffer instead of directly sending messages to processor.
   * This may compensate for situations where input messages are replicated
   * but replicating the corresponding ACKs did not (or only partially) occur
   * during a master failure.
   */

  var inputBuffer = Queue.empty[(Int, Message)]
  var inputBufferSize = 0

  def receive = {
    case RegisterComponents(composite) => {
      components = composite.foldLeft(components) { (a, c) =>
        if (c.processor.isDefined) a + (c.id -> c) else a
      }
    }
    case CompleteReplication => {
      // compute replay starting position for components referenced from input buffer ...
      val replayFrom = inputBuffer.reverse.foldLeft(Map.empty[Int, Long]) { (a, e) =>
        val (cid, m) = e
        a + (cid -> m.sequenceNr)
      }

      // then replay ...
      val replays = for {
        (cid, snr) <- replayFrom
        component  <- components.get(cid)
        processor  <- component.processor
      } yield ReplayInput(cid, snr, processor)

      journal ! BatchReplayInput(replays.toList)

      // and deliver pending output messages
      components.values.foreach(_.deliver())

      // reply that components are recovered
      sender ! ()

      // and ignore further messages
      context.stop(self)
    }

    case cmd: WriteMsg => {
      if (cmd.channelId == Channel.inputChannelId) delay(cmd.componentId, cmd.message)
      journal forward cmd.copy(target = context.system.deadLetters)
    }
    case cmd: WriteAck => {
      journal forward cmd
    }
    case cmd: DeleteMsg => {
      journal forward cmd
    }
  }

  def delay(componentId: Int, msg: Message) {
    inputBuffer = inputBuffer.enqueue(componentId, msg.copy(replicated = true))
    inputBufferSize = inputBufferSize + 1

    if (inputBufferSize > inputBufferLimit) {
      val ((cid, m), b) = inputBuffer.dequeue
      inputBuffer = b
      inputBufferSize = inputBufferSize -1
      for {
        c <- components.get(cid)
        p <- c.processor
      } p ! m
    }
  }
}

object Replicator {
  val counterInitTimeout = Timeout(5 seconds)
  val replicatorTimeout = Timeout(5 seconds)
  val journalTimeout = Timeout(5 seconds)


  case class SetReplicator(replicator: Option[ActorRef])
  case class RegisterComponents(composite: Component)

  case object CompleteReplication

  def complete(replicator: ActorRef, duration: Duration) {
    Await.result(replicator.ask(CompleteReplication)(duration), duration)
  }
}