package org.eligosource.eventsourced.core

import akka.actor._

import DependentStateRecoverySpec._

class DependentStateRecoverySpec extends EventsourcingSpec[Fixture]{
  "Two processors exchanging state-dependent messages" must {
    "be recovered consistently in" in { fixture =>
      import fixture._

      val a1 = processorA()
      val b1 = processorB()

      a1 tell (Message(Increment), b1)
      dequeue() must be ("done")

      // replace registered processor with new ones
      processorA()
      processorB()

      extension.recover()
      dequeue() must be ("done")
    }
  }
}

object DependentStateRecoverySpec {
  class Fixture extends EventsourcingFixture[Any] {
    val target = system.actorOf(Props(new Target(queue)))

    def processorA() = extension.processorOf(Props(new A(target) with Receiver with Eventsourced { val id = 1 }))
    def processorB() = extension.processorOf(Props(new B         with Receiver with Eventsourced { val id = 2 }))
  }

  case object Increment
  case object GetCounter
  case class Counter(value: Int)

  class A(target: ActorRef) extends Actor { this: Receiver =>
    var counter = 0

    def receive = {
      case Increment => {
        counter += 1
        sender ! message.copy(event = GetCounter)
      }
      case Counter(value) => {
        if (counter != (value + 1)) target ! "error"
        if (counter == 5) target ! "done" else sender ! message.copy(event = Increment)
      }
    }
  }

  class B extends Actor { this: Receiver =>
    var counter = 0

    def receive = {
      case Increment => {
        counter += 1
        sender ! message.copy(event = Increment)
      }
      case GetCounter => {
        sender ! message.copy(event = Counter(counter))
      }
    }
  }

  class Target(queue: java.util.Queue[Any]) extends Actor {
    def receive = { case event  => queue.add(event) }
  }
}
