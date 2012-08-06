Eventsourced
============

Eventsourced is a library that adds event-sourcing to [Akka](http://akka.io/) actors and can be used for building scalable, reliable and distributed stateful Scala (web) applications. It appends event (or command) messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Actor state can also be replicated by event-sourcing actor copies on hot-standby slave nodes so that they can start handling new events immediately should a master go down. 

Events produced by an event-sourced actor are sent to destinations via one or more output channels. Channels connect an actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, output channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

From an application developer's perspective there's no difference whether the state of a single actor is recovered (e.g. after a crash or during normal application start) or the state of a network of event-sourced actors (connected via channels to a directed graph which may also contain cycles). The library provides a single method for that. Another benefit of the provided recovery mechanisms is that the implementation of reliable long-running business processes based on event-sourced state machines becomes trivial. For example, Akka's [FSM](http://doc.akka.io/docs/akka/2.0.2/scala/fsm.html) can be used to implement long-running business processes where persistence and recovery is provided by the Eventsourced library.

The library itself is built on top of Akka and all message exchanges performed by the library are asynchronous and non-blocking. Most of the library's building blocks (channels, journal, reliable delivery mechanisms, …) are accessible via actor references. Alternative implementation technologies such as the [Disruptor](http://code.google.com/p/disruptor/) concurrent programming framework will be likely evaluated later. For the journal, [LevelDB](http://code.google.com/p/leveldb/) together with [leveldbjni](https://github.com/fusesource/leveldbjni) are currently used. More information about current development state and limitations is given in section ..

Installation
------------

TODO

First steps
-----------

Let's start with a simple example that demonstrates some basic library usage. The example actor (`Processor`) that will be event-sourced manages orders by consuming `OrderSubmitted` events and producing `OrderAccepted` events once submitted `Order`s have been assigned an id and are stored in memory. 

    import akka.actor._

    // domain events
    case class OrderSubmitted(order: Order)
    case class OrderAccepted(order: Order)

    // domain object
    case class Order(id: Int, details: String, validated: Boolean)

    // event-sourced stateful processor
    class Processor extends Actor {
      var orders = Map.empty[Int, Order]

      def receive = {
        case OrderSubmitted(order) => {
          val id = orders.size
          val upd = order.copy(id = id)
          orders = orders + (id -> upd)
          sender ! Publish("dest", OrderAccepted(upd))
        }
      }
    }

`OrderAccepted` events are published by the processor via the named `"dest"` channel. In this example, the processor instructs the library, via the `Publish` command, to do the publishing. This is a convenient way for processors that generate a single output event for every input event. Processors can also get direct access to output channels using a lower-level API which is useful for more advanced use cases such as generating a stream of output events. We'll se later how to do that.

To actually event-source the processor it needs to be managed inside a `Component`. In addition to the processor, a component also manages the input and output channels for that processor and keeps a reference to the event journal. Processor and output channels of a component are configured by the application.

    import akka.actor._
    import org.eligosource.eventsourced.core._

    // create a journal
    val journalDir = new java.io.File("target/example")
    val journal = system.actorOf(Props(new Journal(journalDir)))

    // create a destination for output events
    val destination = system.actorOf(Props[Destination])

    // create an event-sourced processor
    val processor = system.actorOf(Props[Processor])

    // create and configure an event-sourcing component 
    // with event processor and a named output channel
    val orderComponent = Component(1, journal)
      .addDefaultOutputChannelToActor("dest", destination)
      .setProcessor(processor)

    // recover processor state from journaled events
    orderComponent.init()

The application also sets a component id (`1` in our example) which must be greater than zero and unique per journal. After having configured the component it must be initialized. Component initialization recovers processor state from previous application runs by replaying events to the processor. Output events generated by the processor during a replay are dropped by the output channel if they have been already delivered to the destination before (i.e. have been acknowledged by the destination). Output events that have not been acknowledged are delivered again. A destination acknowledges the receipt of an output event by replying with an 'Ack'. Output events are delivered inside an event `Message`.

    class Destination extends Actor {
      def receive = {
        case msg: Message => { println("received event %s" format msg.event); sender ! Ack }
      }
    }

Replay of events can also start from a certain event sequence number after the processor has been recovered from a state snapshot but this is not shown here. 

The component is now ready to process events. In the following, the application uses the component's `inputProducer` (an actor reference) for sending input events.

    orderComponent.inputProducer ! OrderSubmitted(Order("foo"))
    orderComponent.inputProducer ! OrderSubmitted(Order("bar"))

The component journals these events and sends them to the processor. The output events generated by the processor are sent to the destination via the `dest` output channel. The `Destination` writes the received output events to `stdout`.

    received event OrderAccepted(Order(0,foo,false))
    received event OrderAccepted(Order(1,bar,false))

Note that the order ids `0` and `1` were generated from the size of the order map maintained by the processor. When you exit the application and run it again, the processor's state is recovered and the next two output events will have higher order ids.

    received event OrderAccepted(Order(2,foo,false))
    received event OrderAccepted(Order(3,bar,false))

Diving deeper
-------------

TODO
