package org.eligosource.eventsourced.example

import java.io.File

import scala.concurrent.duration._
import scala.util._

import akka.actor._
import akka.event.Logging._
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal._
import org.eligosource.eventsourced.patterns.aggregate._

object ScatterGatherExample extends App {
  import IncrementProcess._
  import Counter._

  val config = ConfigFactory.load("scatter")
  val configCommon = config.getConfig("common")

  implicit val system = ActorSystem("scatter", config.getConfig("process").withFallback(configCommon))
  implicit val timeout = Timeout(30 seconds)

  import system.dispatcher

  // TODO: investigate issues with LeveldbJournalProps.withSequenceStructure
  // TODO: investigate issues with JournalioJournalProps
  // - 1st run ok
  // - 2nd run unexpected results

  val journal: ActorRef = Journal(LeveldbJournalProps(new File("target/counter")))
  val extension = EventsourcingExtension(system, journal)

  // external services
  val counter1 = system.actorFor("akka://counter@127.0.0.1:2673/user/counter1")
  val counter2 = system.actorFor("akka://counter@127.0.0.1:2674/user/counter2")
  val counter3 = system.actorFor("akka://counter@127.0.0.1:2675/user/counter3")

  // debug replays and resuming processes
  system.eventStream.subscribe(system.actorOf(Props[DeadLetterResponseLogger]), classOf[DeadLetter])

  // create single process instance
  val process = extension.processorOf(Props(new IncrementProcess(1) with GatherIdempotency with Receiver with Eventsourced))

  // add (external) services to the process instance
  process ! AddCounter(counter1)
  process ! AddCounter(counter2)
  process ! AddCounter(counter3)

  // set some failure conditions
  counter2 ! FailOn(2) // send failure reply on increment of 2
  //counter1 ! FailOn(3) // send failure reply on increment of 3

  val pid = System.currentTimeMillis()

  for {
    _ <- process ? Recover(timeout)
    inc2 <- process ? Message(Increment(2, processId = pid + 1)) // will fail
    inc3 <- process ? Message(Increment(3, processId = pid + 2)) // will fail
    inc4 <- process ? Message(Increment(4, processId = pid + 3)) // will succeed
  } {
    println("Increment 2: %s" format inc2)
    println("Increment 3: %s" format inc3)
    println("Increment 4: %s" format inc4)
  }

  // ------------------------------------------------------------
  //  Logging utilities
  // ------------------------------------------------------------

  class DeadLetterResponseLogger extends Actor {
    def receive = {
      case DeadLetter(msg: String, _, _) => println("dead letter: %s" format msg)
    }
  }

  class NoLogger extends Actor with StdOutLogger {
    override def receive: Receive = {
      case InitializeLogger(_) => sender ! akka.event.Logging.LoggerInitialized
      case event: LogEvent     => // mute
    }
  }
}

// ------------------------------------------------------------
//  Event-sourced business process (based on scatter-gather)
// ------------------------------------------------------------

/**
 * Accepts `Increment` commands and sends them to n `Counter` actors. If one or
 * more counter actors reply with a failure, this process attempts to compensate
 * the increments on all counters. On reply timeout, an increment is retried up
 * to three times (for non-replying counters only). Compensations are retried as
 * well. If the JVM is killed while the process is running and then re-started,
 * the process is resumed from the point where it failed. A counter can detect
 * duplicates and ignores compensations for which no corresponding previous
 * increments has been successfully processed.
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
      val sg = new ScatterGather(this, targets.map((_ -> inc.copy(compensation = false, clientId = id))))
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
        .withRedeliveryMax(3)
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
      become(idle) // accept new increments
      initiator ! "success"
    } else {
      val sg = new ScatterGather(this, (result.gatheredSuccess ++ result.gatheredFailure) map {
        case g: Gathered => {
          val Increment(inc, _, cid, pid) = g.msg
          // targeted compensation message
          g.target -> Increment(-inc, true, cid, pid)
        }
      })
      // scatter compensation messages to all targets
      sg.scatter()
      // switch to 'gathering' behavior and call
      // onComplete when all replies have been gathered
      sg.gather { result =>
        become(idle) // accept new increments
        initiator ! "failure"
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
  import Counter._
  import context._

  implicit val timeout = Timeout(1 seconds)

  def scattered(msg: Any, redeliveries: Int, onSuccess: (Any) => Unit, onFailure: (Any) => Unit) = msg match {
    case inc: Increment => {
      println("sending increment %s to %s" format (inc, counter))

      val future = counter ? inc

      future onSuccess {
        case s: IncrementSuccess => onSuccess(s)
        case f: IncrementFailure => onFailure(f)
      }
      future onFailure {
        case t => if (redeliveries > 2) onFailure(IncrementFailure("increment timeout", inc.value))
      }
    }
  }
}

// ------------------------------------------------------------
//  External counter service(s)
// ------------------------------------------------------------

class Counter(name: String) extends Actor { this: CounterIdempotency =>
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
        accept(inc)
        sender ! IncrementSuccess(counter)
      }
    }
    case FailOn(d) => failOn = d
  }
}

trait CounterIdempotency extends Actor { this: Counter =>
  import Counter._

  // clientId -> (lastProcessId, compensation)
  var lastProcesses = Map.empty[Int, (Long, Boolean)]

  abstract override def receive = {
    case inc @ Increment(_, compensation, clientId, processId) => lastProcesses.get(clientId) match {
      case None => if (compensation) onPhantomCompensation(inc) else super.receive(inc)
      case Some((lastProcessId, lastCompensation)) => {
        if      (processId < lastProcessId) onDuplicate(inc)
        else if (processId == lastProcessId && compensation <= lastCompensation) onDuplicate(inc)
        else if (processId > lastProcessId && compensation) onPhantomCompensation(inc)
        else super.receive(inc)
      }
    }
    case msg => super.receive(msg)
  }

  def accept(inc: Increment) {
    lastProcesses = lastProcesses + (inc.clientId -> (inc.processId, inc.compensation))
  }

  def onDuplicate(inc: Increment) {
    println("ignore increment duplicate: %s" format inc)
    sender ! IncrementSuccess(counter)
  }

  def onPhantomCompensation(inc: Increment) {
    println("ignore phantom compensation: %s" format inc)
    sender ! IncrementFailure("phantom compensation", inc.value)
  }
}

object Counter extends App {
  case class Increment(
    value: Int,                    // increment value
    compensation: Boolean = false, // compensating increment
    clientId: Int = 0,             // identifies the sending processor (needed for duplicate detection)
    processId: Long = 0L)          // identifies the sending process instance (needed for duplicate detection)
  case class IncrementSuccess(counter: Int)
  case class IncrementFailure(message: String, value: Int)
  case class FailOn(increment: Int)
}

trait CounterApp extends App {
  val config = ConfigFactory.load("scatter")
  val configCommon = config.getConfig("common")

  val system = ActorSystem("counter", config.getConfig(counterName).withFallback(configCommon))
  val counter = system.actorOf(Props(new Counter(counterName) with CounterIdempotency), counterName)

  def counterName = "counter%d" format counterNr
  def counterNr: Int
}

object Counter1 extends CounterApp { def counterNr = 1 }
object Counter2 extends CounterApp { def counterNr = 2 }
object Counter3 extends CounterApp { def counterNr = 3 }
