Eventsourced
============

Eventsourced is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors and can be used for building scalable, reliable and distributed stateful Scala (web) applications. It appends event (or command) messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Actor state can also be replicated by event-sourcing actor copies on hot-standby slave nodes so that they can start handling new events immediately should a master go down. 

Events produced by an event-sourced actor are sent to destinations via one or more output channels. Channels connect an actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, output channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

From an application developer's perspective there's no difference whether the state of a single actor is recovered (e.g. after a crash or during normal application start) or the state of a network of event-sourced actors (connected via channels to a directed graph which may also contain cycles). The library provides a single method for that. Another benefit of the provided recovery mechanisms is that the implementation of reliable long-running business processes based on event-sourced state machines becomes trivial. For example, Akka's [FSM](http://doc.akka.io/docs/akka/2.0.2/scala/fsm.html) can be used to implement long-running business processes where persistence and recovery is provided by the Eventsourced library.

The library itself is built on top of Akka and all message exchanges performed by the library are asynchronous and non-blocking. Most of the library's building blocks (channels, journal, reliable delivery mechanisms, …) are accessible via actor references. Alternative implementation technologies such as the [Disruptor](http://code.google.com/p/disruptor/) concurrent programming framework will be likely evaluated later. For the journal, [LevelDB](http://code.google.com/p/leveldb/) together with [leveldbjni](https://github.com/fusesource/leveldbjni) are currently used. More information about the current development status and limitations is given in section [Current status](#current-status).

Installation
------------

See [Installation](eventsourced/wiki/Installation) Wiki page.

First steps
-----------

Let's start with a simple example that demonstrates some basic library usage. This example is part of the project's test sources and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.example.OrderExample1'`. The example actor (`Processor`) that will be event-sourced manages orders by consuming `OrderSubmitted` events and producing `OrderAccepted` events once submitted `Order`s have been assigned an id and are stored in memory. 

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

`OrderAccepted` events are published by the processor via the named `"dest"` channel. In this example, the processor instructs the library, via the `Publish` command, to do the publishing. This is a convenient way for processors that generate a single output event for every input event. Processors can also get direct access to output channels using a lower-level API which is useful for more advanced use cases such as generating a stream of output events. We'll see later how to do that.

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

The application also sets a component id (`1` in our example) which must be greater than zero and unique per journal. After having configured the component it must be initialized. Component initialization recovers processor state from previous application runs by replaying events to the processor. Output events generated by the processor during a replay are dropped by the output channel if they have been already delivered to the destination before (i.e. have been acknowledged by the destination). Output events that have not been acknowledged are delivered again. A destination acknowledges the receipt of an output event by replying with an 'Ack'. Output events are delivered within an event `Message`.

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

In the [previous section](#first-steps) the application-provided processor was wrapped by a library-provided processor. The library-provided processor extracted the input event from an [event message](https://github.com/eligosource/eventsourced/blob/master/src/main/scala/org/eligosource/eventsourced/core/Message.scala), sent the extracted event to the application-provided processor and received an output event from that processor. The library-provided processor also had a reference the component's output channel so that it could send the received output event to that channel's destination.

Application-defined processors may also receive input event messages directly and obtain a reference to output channels. In this case, `setProcessor` must be called with an actor reference factory of type `Map[String, ActorRef] => ActorRef` where the factory parameter is an output channel map. This is shown in the following snippet.

    class Processor(outputChannels: Map[String, ActorRef]) extends Actor {
      var orders = Map.empty[Int, Order] // processor state

      def receive = {
        case msg: Message => msg.event match {
          case OrderSubmitted(order) => {
            val id = orders.size
            val upd = order.copy(id = id)
            orders = orders + (id -> upd)
            outputChannels("dest") ! msg.copy(event = OrderAccepted(upd))
          }
        }
      }
    }
 
    val orderComponent = Component(1, journal)
      .addDefaultOutputChannelToActor("dest", destination)
      .setProcessor(outputChannels => system.actorOf(Props(new Processor(outputChannels))))

Here, the processor extracts the `OrderSubmitted` event from the input message (`msg`) and sends a modified output event message to the `dest` channel (an actor reference obtained from the `outputChannels` map). This example is also part of the project's test sources and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.example.OrderExample2'`.

Processor implementations may also choose to send several output event messages or none at all. In case of several output messages, the processor must indicate to the output channel that it should skip writing acknowledgements for all but the last output message. This can be done by setting the `ack` field for all but the last output message to `false` via `msg.copy(ack = false, event ...)`. This is important to avoid message loss during recovery after a crash, for example. 

In general, the library always relies on event messages to communicate events. This applies to input and output channels, interactions with the processor and output channel destinations. For example, when sending an input event via a component's input channel it must be contained inside an event message. 

    orderComponent.inputChannel ! Message(OrderSubmitted(Order("foo")))
    orderComponent.inputChannel ! Message(OrderSubmitted(Order("bar")))

The [previous section](#first-steps) just made use of some higher-level API supporting simpler use cases. By using event messages directly, event-sourced processors have access to additional library features.

- They can reply to an initial message sender (an actor reference) which can be obtained from the message's `sender` field. The initial sender can be transmitted with an event message across any number of components. Replying to an event message sender, for example, can be useful in web framework controllers where the controller expects an asynchronous response from an event-sourced processor. One way to set a sender reference on an input event message is to send an input event via a component's input producer using the `?` method.

        import akka.actor._
        import akka.pattern.ask

        orderComponent.inputProducer ? OrderSubmitted(Order("baz")) onSuccess {
          case result => ...
        }

    This returns a future to the caller which can be used to register an `onSuccess` callback. When the processor replies to the sender, the future will be completed.

        class Processor(outputChannels: Map[String, ActorRef]) extends Actor {
          var orders = Map.empty[Int, Order] // processor state

          def receive = {
            case msg: Message => msg.event match {
              case OrderSubmitted(order) => {
                val response = ...
                // reply to initial sender
                msg.sender.foreach(_ ! response)
                ...                
              }
            }
          }
        }
    
    Applications may of course set any other actor reference as message sender. Replies to senders are not journaled.

- The library guarantees at-least-once message delivery. Some messages may be delivered more than once during recovery from a crash or after failover. Processors can use the message's `senderMessageId` for detecting duplicates (and become idempotent receivers). The `senderMessageId` is set by message producers e.g. external clients or event-sourced processors that send event messages to other processors.

- The message sequence number can be obtained from the `sequenceNr` field. Sequence numbers are unique per journal and required for taking snapshots of processor state. The highest sequence number that contributes to a processor's state change must be included in the snapshot so that the library can determine which past events are covered by that snapshot. Processors may also use the sequence number to derive a `senderMessageId` so that downstream processors are able to detect duplicates if necessary.

External services
-----------------

TODO

State machines
--------------

TODO

More features
-------------

TODO

Current status
--------------

TODO

Forum
-----

[Eventsourced](http://groups.google.com/group/eventsourced) Google Group