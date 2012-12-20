package org.eligosource.eventsourced.example

import java.io.File

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal._
import org.eligosource.eventsourced.patterns.aggregate._

object CounterExample extends App {
  import IncrementProcess._
  import Counter._

  implicit val system = ActorSystem("guide")
  implicit val timeout = Timeout(30 seconds)

  import system.dispatcher

  val journal: ActorRef = Journal(LeveldbJournalProps(new File("target/process")))
  val extension = EventsourcingExtension(system, journal)

  // external services (running locally in this example)
  val counter1 = system.actorOf(Props(new Counter("counter 1") with CounterIdempotency))
  val counter2 = system.actorOf(Props(new Counter("counter 2") with CounterIdempotency))
  val counter3 = system.actorOf(Props(new Counter("counter 3") with CounterIdempotency))

  // set some failure conditions
  counter2 ! FailOn(2) // send failure reply on increment of 2
  counter1 ! FailOn(3) // send failure reply on increment of 3

  // create single process instance
  val process = extension.processorOf(Props(new IncrementProcess(1) with GatherIdempotency with Receiver with Eventsourced))

  // add (external) services to the process instance
  process ! AddCounter(counter1)
  process ! AddCounter(counter2)
  process ! AddCounter(counter3)

  for {
    _ <- process ? Recover(timeout)
    _ <- process ? Message(Increment(2)) // will fail after 3 retries
    _ <- process ? Message(Increment(3)) // will fail after failure reply
    _ <- process ? Message(Increment(4)) // will succeed
  } ()

  Thread.sleep(1000)
  system.shutdown()
}

// ------------------------------------------------------------
//  Event-sourced business process (based on scatter-gather)
// ------------------------------------------------------------

/**
 * Accepts `Increment` commands and sends them to n `Counter` actors. If one or
 * more counter actors reply with a failure, this process compensates increments
 * on the other counter actors. On reply timeout, an increment is retried up to
 * three times (for non-replying actors). If an actor still doesn't respond within
 * the retry period, the increment attempt is considered failed. Compensations are
 * retried as well. Communication with this actor is logged so that the process state
 * can be recovered after JVM crashes (and the process execution resumed from the
 * point where it failed).
 */
class IncrementProcess(val id: Int) extends Actor { this: Receiver with Eventsourced =>
  import IncrementProcess._
  import Counter._

  var targets: List[ActorRef] = Nil
  var initiator: ActorRef = context.system.deadLetters

  def receive = idle

  val idle: Receive = {
    case inc: Increment => {
      initiator = sender
      val sg = new ScatterGather(this, targets.map((_ -> inc.copy(compensation = false, clientId = id, processId = message.sequenceNr))))
      // scatter increments to external counters
      sg.scatter()
      // switch to 'gathering' behavior and call
      // onComplete when all replies have been gathered
      sg.gather(onComplete)
    }
    case AddCounter(counter) => {
      // create a proxy for the (external) counter
      val proxy = context.actorOf(Props(new CounterProxy(counter) with Receiver))
      // communicate with proxy via a reliable channel
      // (needed for retrying when counter is unavailable)
      val channel = extension.channelOf(ReliableChannelProps(id + targets.size + 1, proxy)
        .withRedeliveryDelay(0 seconds)
        .withConfirmationTimeout(2 seconds))

      proxy ! ScatterTarget.SetScatterSource(self)
      targets = channel :: targets
    }
    case Recover(timeout) => {
      import context.dispatcher
      val initiator = sender
      val composite = for {
        _ <- extension.replay(pid => if (pid == id) Some(0L) else None)(timeout)
        _ <- extension.deliver(targets)(timeout)
      } yield ()

      composite onSuccess {
        case _ => initiator ! Recovered
      }
    }
  }

  def onComplete(result: GatherResult) {
    if (result.gatheredFailure.isEmpty) {
      println("increment succeeded")
      become(idle) // accept new increments
      initiator ! "increment succeeded"
    } else {
      println("increment failed")
      val sg = new ScatterGather(this, result.gatheredSuccess.map {
        case GatheredSuccess(target, Increment(inc, _, cid, pid), _) => {
          target -> Increment(-inc, true, cid, pid) // compensation message
        }
      })
      // scatter compensation messages to targets
      sg.scatter()
      // switch to 'gathering' behavior and call
      // onComplete when all replies have been gathered
      sg.gather { result =>
        println("compensation done")
        become(idle) // accept new increments
        initiator ! "increment failed"
      }
    }
  }
}

object IncrementProcess {
  case class AddCounter(counter: ActorRef)
  case class Recover(timeout: Timeout)
  case object Recovered
}

/**
 * Proxy for an (external) `Counter` actor.
 */
class CounterProxy(counter: ActorRef) extends ScatterTarget { this: Receiver =>
  implicit val timeout = Timeout(1 seconds)

  import Counter._
  import context._

  var timeoutCount = 0;

  def scattered(msg: Any, redeliveries: Int, onSuccess: (Any) => Unit, onFailure: (Any) => Unit) = msg match {
    case r: Increment => {
      val future = counter ? r
      future onSuccess {
        case s: IncrementSuccess => onSuccess(s)
        case f: IncrementFailure => onFailure(f)
      }
      future onFailure {
        case t => if (redeliveries > 2) onFailure(IncrementFailure("increment timeout", r.value))
      }
    }
  }
}

// ------------------------------------------------------------
//  External service
// ------------------------------------------------------------

/**
 * Stateful counter service.
 */
class Counter(name: String) extends Actor {
  import Counter._

  var failOn: Int = 0
  var counter = 0

  def receive = {
    case inc @ Increment(value, _, _, _) => {
      if (failOn == inc.value) {
        println("%s: state = %d, increment = %s, rejected" format (name, counter, inc))
        sender ! IncrementFailure("invalid increment", value)
      } else {
        counter += value
        println("%s: state = %d, increment = %s, accepted" format (name, counter, inc))
        sender ! IncrementSuccess(counter)
      }
    }
    case FailOn(d) => failOn = d
  }
}

trait CounterIdempotency extends Actor { this: Counter =>
  import Counter._

  // clientId -> (lastProcessId, compensation)
  var processes = Map.empty[Int, (Long, Boolean)]

  abstract override def receive = {
    case inc @ Increment(_, compensation, clientId, processId) => processes.get(clientId) match {
      case Some((`processId`, `compensation`)) => println("duplicate detected and dropped %s" format inc)
      case _ => { super.receive(inc); processes = processes + (clientId -> (processId, compensation)) }
    }
    case msg => super.receive(msg)
  }
}

object Counter extends App {
  case class Increment(
    value: Int,                    // increment value
    compensation: Boolean = false, // whether this is compensation
    clientId: Int = 0,             // identifies the sending client (needed for duplicate detection)
    processId: Long = 0L)          // identifies the sending process (sending 1 increment and 0..1 compensations)
  case class IncrementSuccess(counter: Int)
  case class IncrementFailure(message: String, value: Int)
  case class FailOn(increment: Int)

  val system = ActorSystem("counter")
  val counter = system.actorOf(Props[Counter], "counter")
}
