package org.eligosource.eventsourced.core

import akka.actor._

import ProcessorsSpec._

class ProcessorsSpec extends EventsourcingSpec[Fixture] {
  "A multicast processor" must {
    "forward received event messages to its targets" in { fixture =>
      import fixture._

      multicast ! Message("test")

      dequeue() must be ("test")
      dequeue() must be ("test")
    }
    "forward received non-event messages to its targets" in { fixture =>
      import fixture._

      multicast ! "blah"

      dequeue() must be ("blah")
      dequeue() must be ("blah")
    }
  }
  "A receiver" must {
    "support behavior changes and overriding unhandled" in { fixture =>
      import fixture._

      changing ! Message("foo")
      changing ! Message("bar")
      changing ! Message("baz")

      dequeue() must be ("foo")
      dequeue() must be ("bar")
      dequeue() must be ("baz")

      changing ! Message("xyz")

      dequeue() must be ("unhandled")
    }
  }
}

object ProcessorsSpec {
  class Fixture extends EventsourcingFixture[Any] {
    val destination = system.actorOf(Props(new Destination(queue) with Emitter))

    val changing = extension.processorOf(ProcessorProps(2, new Changing with Emitter with Eventsourced))
    val multicast = extension.processorOf(ProcessorProps(1, org.eligosource.eventsourced.core.multicast(List(
      system.actorOf(Props(new Target with Emitter)),
      system.actorOf(Props(new Target with Emitter))
    ))))

    extension.channelOf(DefaultChannelProps(1, destination).withName("dest"))
    extension.recover()
  }

  class Changing extends Actor { this: Emitter =>
    val changed: Receive = {
      case "bar" => { emitter("dest").emitEvent("bar"); context.unbecome() }
    }

    def receive = {
      case "foo" => { emitter("dest").emitEvent("foo"); context.become(changed) }
      case "baz" => { emitter("dest").emitEvent("baz") }
    }

    override def unhandled(msg: Any) = msg match {
      case s: String => emitter("dest").emitEvent("unhandled")
      case _         => super.unhandled(msg)
    }
  }

  class Target extends Actor { this: Emitter =>
    def receive = {
      case "blah" => channels("dest") ! Message("blah", ack = false)
      case event  => emitter("dest").emitEvent(event)
    }
  }

  class Destination(queue: java.util.Queue[Any]) extends Actor { this: Receiver =>
    def receive = {
      case event => queue.add(event)
    }
  }
}
