package org.eligosource.eventsourced.example

import java.io.File

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal._
import org.eligosource.eventsourced.patterns._

object CounterExample extends App {
  import IncrementProcess._
  import Counter._

  implicit val system = ActorSystem("guide")
  implicit val timeout = Timeout(30 seconds)

  import system.dispatcher

  val journal: ActorRef = Journal(LeveldbJournalProps(new File("target/process")))
  val extension = EventsourcingExtension(system, journal)

  // external services (running locally in this example)
  val counter1 = system.actorOf(Props(new Counter("counter 1")))
  val counter2 = system.actorOf(Props(new Counter("counter 2")))
  val counter3 = system.actorOf(Props(new Counter("counter 3")))

  // set some failure conditions
  counter2 ! MuteOn(2) // ignore increment of 2 and do not respond
  counter1 ! FailOn(3) // send failure reply on increment of 3

  // create single process instance
  val process = extension.processorOf(Props(new IncrementProcess(1) with Receiver with Eventsourced))

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

  Thread.sleep(10000)
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
      val sg = new ScatterGather(this, targets.map((_ -> inc)))
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

      proxy ! SetScatterSource(self)
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
        case GatheredSuccess(target, Increment(delta), _) => target -> Increment(-delta) // compensation messages
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
        case t => if (redeliveries > 2) onFailure(IncrementFailure("increment timeout", r.delta))
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
  var muteOn: Int = 0
  var value = 0

  def receive = {
    case Increment(d) => {
      if (failOn == d) {
        println("%s: state = %d, reject = %d" format (name, value, d))
        sender ! IncrementFailure("invalid delta", d)
      } else {
        if (muteOn != d) {
          value += d
          println("%s: state = %d, accept = %d" format (name, value, d))
          sender ! IncrementSuccess(value)
        } else {
          println("%s: state = %d, no service" format (name, value))
        }
      }
    }
    case FailOn(d)    => failOn = d
    case MuteOn(d)    => muteOn = d
    case Get          => sender ! value
  }
}

object Counter extends App {
  case class Increment(delta: Int)
  case class IncrementSuccess(value: Int)
  case class IncrementFailure(message: String, delta: Int)

  case class FailOn(delta: Int)
  case class MuteOn(delta: Int)

  case object Get

  val system = ActorSystem("counter")
  val counter = system.actorOf(Props[Counter], "counter")
}
