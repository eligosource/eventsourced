Eventsourced
============

Eventsourced is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors and can be used for building scalable, reliable and distributed stateful Scala (web) applications. It appends event (or command) messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Actor state can also be replicated by event-sourcing actor copies on hot-standby slave nodes so that they can start handling new events immediately should a master go down. 

Events produced by an event-sourced actor are sent to destinations via one or more output channels. Channels connect an actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, output channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

From an application developer's perspective there's no difference whether the state of a single actor is recovered (e.g. after a crash or during normal application start) or the state of a network of event-sourced actors (connected via channels to a directed graph which may also contain cycles). The library provides a single method for that. Another benefit of the provided recovery mechanisms is that the implementation of reliable long-running business processes based on event-sourced state machines becomes trivial. For example, Akka's [FSM](http://doc.akka.io/docs/akka/2.0.2/scala/fsm.html) can be used to implement long-running business processes where persistence and recovery is provided by the Eventsourced library.

The library itself is built on top of Akka and all message exchanges performed by the library are asynchronous and non-blocking. Most of the library's building blocks (channels, journal, reliable delivery mechanisms, …) are accessible via actor references. Alternative implementation technologies such as the [Disruptor](http://code.google.com/p/disruptor/) concurrent programming framework will likely be evaluated later. The journal implementation is currently based on [LevelDB](http://code.google.com/p/leveldb/) and [leveldbjni](https://github.com/fusesource/leveldbjni). More information about the current development status is given in section [Current status](#current-status).

Installation
------------

See [Installation](eventsourced/wiki/Installation) Wiki page.

First steps
-----------

Let's start with a simple example that demonstrates some basic library usage. This example is part of the project's test sources and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.example.OrderExample1'` (see the [Installation](eventsourced/wiki/Installation) Wiki page for details about the `run-nobootcp` task). The example actor (`Processor`) that will be event-sourced manages orders by consuming `OrderSubmitted` events and producing `OrderAccepted` events once submitted `Order`s have been assigned an id and are stored in memory. 

    import akka.actor._

    // domain events
    case class OrderSubmitted(order: Order)
    case class OrderAccepted(order: Order)

    // domain object
    case class Order(id: Int, details: String, validated: Boolean, creditCardNumber: String)

    object Order {
      def apply(details: String): Order = apply(details, "")
      def apply(details: String, creditCardNumber: String): Order = new Order(-1, details, false, creditCardNumber)
    }

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

`OrderAccepted` events are published by the processor via the named `"dest"` channel. In this example, the processor instructs the library, via the `Publish` command, to do the publishing. This is a convenient way for processors that generate a single output event for every input event. Processors can also get direct access to output channels using a lower-level API which is useful for more advanced use cases such as generating a stream of output events, for example. We'll see later how to do that.

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

The application also sets a component id (`1` in our example) which must be greater than zero and unique per journal. After having configured the component it must be initialized. Component initialization recovers processor state from previous application runs by replaying events to the processor. Output events generated by the processor during a replay are dropped by the output channel if they have been already delivered to the destination before (i.e. have been acknowledged by the destination). Output events that have not been acknowledged are delivered again. A destination acknowledges the receipt of an output event by replying with an 'Ack'. Output events are delivered within an event message of type [`Message`](https://github.com/eligosource/eventsourced/blob/master/src/main/scala/org/eligosource/eventsourced/core/Message.scala) where the `event` field holds the actual event (more on that in the [next section](#diving-deeper)). <a name="destination"/>

    class Destination extends Actor {
      def receive = {
        case msg: Message => { println("received event %s" format msg.event); sender ! Ack }
      }
    }

Replay of events can also start from a certain event sequence number after the processor has been recovered from a state snapshot but this is not shown here. 

The component is now ready to process events. In the following, the application uses the component's `inputProducer` (an actor reference) for sending input events.

    orderComponent.inputProducer ! OrderSubmitted(Order("foo"))
    orderComponent.inputProducer ! OrderSubmitted(Order("bar"))

The component journals these events and sends them to the processor. Output events generated by the processor are sent to `destination` via the `dest` output channel. The example destination writes the received output events to `stdout`.

    received event OrderAccepted(Order(0,foo,false,))
    received event OrderAccepted(Order(1,bar,false,))

Note that in our example, the order ids `0` and `1` are derived from the size of the order map maintained by the processor. When you exit the application and run it again, the processor's state is recovered and the next two output events will have higher order ids.

    received event OrderAccepted(Order(2,foo,false,))
    received event OrderAccepted(Order(3,bar,false,))

Diving deeper
-------------

In the [previous section](#first-steps) the application-provided processor was wrapped by a library-provided processor. The library-provided processor extracted the input event from an event message, sent the extracted event to the application-provided processor and received an output event from that processor. The library-provided processor also had a reference the component's output channel so that it could send the received output event to that channel's destination.

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

Processor implementations may also choose to send several output event messages or none at all. In case of several output messages, the processor must indicate to the output channel that it should skip writing acknowledgements for all but the last output message. This can be done by setting the `ack` field for all but the last output message to `false` via `msg.copy(ack = false, event = …)`. This is important to avoid message loss during recovery after a crash, for example. 

In general, the library always uses event messages to communicate events. This applies to input and output channels, interactions with the processor and output channel destinations. For example, when sending an input event via a component's input channel it must be contained inside an event message. 

    orderComponent.inputChannel ! Message(OrderSubmitted(Order("foo")))
    orderComponent.inputChannel ! Message(OrderSubmitted(Order("bar")))

The [previous section](#first-steps) just made use of some higher-level API supporting simpler use cases. By using [event messages](https://github.com/eligosource/eventsourced/blob/master/src/main/scala/org/eligosource/eventsourced/core/Message.scala) directly, event-sourced processors have access to additional library features.

- They can reply to an initial message sender (an actor reference) that is held by the message's `sender` field. The initial sender can be transmitted with an event message across any number of components. Replying to an event message sender, for example, can be useful in web framework controllers where the controller expects an asynchronous response from an event-sourced processor. One way to set a sender reference on an input event message is to send an input event via a component's `inputProducer` using the `?` method.

        import akka.actor._
        import akka.pattern.ask

        orderComponent.inputProducer ? OrderSubmitted(Order("baz")) onSuccess {
          case result => … 
        }

    This returns a future to the caller which can be used to register an `onSuccess` callback. When the processor replies to the sender, the future will be completed.

        class Processor(outputChannels: Map[String, ActorRef]) extends Actor {
          … 
          
          def receive = {
            case msg: Message => msg.event match {
              case OrderSubmitted(order) => {
                val response = … 
                // reply to initial sender
                msg.sender.foreach(_ ! response)
                … 
              }
            }
          }
        }
    
    Applications may of course set any other actor reference as message sender. Replies to senders are not journaled.

- The library guarantees at-least-once message delivery. Some messages may be delivered more than once during recovery from a crash or after failover. Processors can use the message's `senderMessageId` for detecting duplicates (and become idempotent receivers). The `senderMessageId` is set by message producers e.g. external clients or event-sourced processors that send event messages to other processors.

- The `sequenceNr` field hold's the message's sequence number. Sequence numbers are unique per journal and are required for taking snapshots of processor state. The highest sequence number that contributes to a processor's state change must be included in the snapshot so that the library (or the application) can determine which past events are covered by that snapshot. Processors may also use the sequence number to derive a `senderMessageId` for output event messages so that downstream processors or other destinations are able to detect duplicates, if necessary.

External services
-----------------

In this section we'll extend the order management example (developed in [previous](#first-steps) [sections](#diving-deeper)) to integrate an external service and implement what [Martin Fowler](http://www.martinfowler.com/) describes in his great [LMAX article](http://martinfowler.com/articles/lmax.html):

> Imagine you are making an order for jelly beans by credit card. A simple retailing system would take your order information, use a credit card validation service to check your credit card number, and then confirm your order - all within a single operation. The thread processing your order would block while waiting for the credit card to be checked, but that block wouldn't be very long for the user, and the server can always run another thread on the processor while it's waiting.
>
> In the LMAX architecture, you would split this operation into two. The first operation would capture the order information and finish by outputting an event (credit card validation requested) to the credit card company. The Business Logic Processor would then carry on processing events for other customers until it received a credit-card-validated event in its input event stream. On processing that event it would carry out the confirmation tasks for that order.

What's described in the second paragraph can be implemented with the Eventsourced library as shown in the following diagram.

![Order example](https://raw.github.com/eligosource/eventsourced/master/doc/images/order-example-3.png)

- We implement the mentioned <i>Business Logic Processor</i> processor as event-sourced actor (`OrderProcessor`). It processes `OrderSubmitted` events and emits `CreditCardValidationRequested` events for every submitted order.
- `CreditCardValidationRequested` events are processed by a `CreditCardValidator` actor. It contacts an external credit card validation service and sends `CreditCardValidated` events back to the order management component for every order with a valid credit card number. In the example implementation below, we won't actually use an external service to keep the implementation simple, but for real-world implementations, [akka-camel](http://doc.akka.io/docs/akka/snapshot/scala/camel.html) would be a perfect fit here.
- The event-sourced `OrderProcessor` finally updates the status of all orders, for which it receives a `CreditCardValidated` event, to `validated = true` and sends `OrderAccepted` events, containing the updated orders, to the final `Destination`. It also replies updated orders to the initial sender. 

The complete example is available at [OrderExample3.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/example/OrderExample3.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.example.OrderExample3'`. Notable changes to previous sections are:

- The component is now configured with two output channels, one for sending events to the `CreditCardValidator`, another one for sending events to the final `Destination`. The output channel to the `CreditCardValidator` is a reliable output channel which will re-deliver output messages should the credit card validator reply with a (system-level) failure message or after a timeout. This output channel is also configured with a so-called <i>reply destination</i>, a component where replies from the credit card validator should be sent to. In our example, this is the same component that also emits events to the credit card validator.

        val validator = system.actorOf(Props[CreditCardValidator])
        val destination = system.actorOf(Props[Destination])

        val orderComponent = Component(1, journal)

        orderComponent
          .addReliableOutputChannelToActor("validator", validator, Some(orderComponent))
          .addDefaultOutputChannelToActor("destination", destination)
          .setProcessor(outputChannels => system.actorOf(Props(new OrderProcessor(outputChannels))))

- The `OrderProcessor` now handles both `OrderSubmitted` and `CreditCardValidated` events and emits `CreditCardValidationRequested` and `OrderAccepted` events to different output channels.

        class OrderProcessor(outputChannels: Map[String, ActorRef]) extends Actor {
          var orders = Map.empty[Int, Order] // processor state
      
          def receive = {
            case msg: Message => msg.event match {
              case OrderSubmitted(order) => {
                val id = orders.size
                val upd = order.copy(id = id)
                orders = orders + (id -> upd)
                outputChannels("validator") ! msg.copy(event = CreditCardValidationRequested(upd))
              }
              case CreditCardValidated(orderId) => {
                orders.get(orderId).foreach { order =>
                  val upd = order.copy(validated = true)
                  orders = orders + (orderId -> upd)
                  msg.sender.foreach(_ ! upd)
                  outputChannels("destination") ! msg.copy(event = OrderAccepted(upd))
                }
              }
            }
          }
        }

- The `CreditCardValidator` asynchronously validates credit card numbers (which can be obtained from orders contained in `CreditCardValidationRequested` events) and replies with `CreditCardValidated` events (integration with validation service not shown). The replied event messages are sent by the library to the configured reply destination (i.e to the `orderComponent`).

        class CreditCardValidator extends Actor {
          def receive = {
            case msg: Message => msg.event match {
              case CreditCardValidationRequested(order) => {
                val s = sender
                Future {
                  // do some credit card validation asynchronously
                  // ...
      
                  // and send back a successful validation result
                  s ! msg.copy(event = CreditCardValidated(order.id))
                }
              }
            }
          }
        }

Please not that all message exchanges in this example are asynchronous and non-blocking, no thread is blocked waiting for an event message to arrive.

State machines
--------------

Event-sourcing Akka [finite state machines (FSMs)](http://doc.akka.io/docs/akka/2.0.2/scala/fsm.html) is equally easy as event-sourcing any other actor. For demonstration purposes we'll follow the approach from section [First steps](#first-steps) and wrap an FSM inside a library-provided processor (see also section [Diving deeper](#diving-deeper) for details and alternatives). 

The event-sourced FSM in the following example is a `Door` which can be in one of two states: `Open` and `Closed`. Any change to a door's state is communicated to a destination (via the `dest` output channel) with a `DoorMoved` event containing the number of moves so far. Any invalid attempt to move a door e.g. trying to open an opened door emits a `DoorNotMoved` event.

    import akka.actor._
    import org.eligosource.eventsourced.core._

    sealed trait DoorState

    case object Open extends DoorState
    case object Closed extends DoorState

    case class DoorMoved(times: Int)
    case class DoorNotMoved(reason: String)

    class Door extends Actor with FSM[DoorState, Int] {
      startWith(Closed, 0 /* number of moves */) 
  
      when(Closed) {
        case Event("open", counter) => goto(Open) using(counter + 1) replying(Publish("dest", DoorMoved(counter + 1)))
      }
  
      when(Open) {
        case Event("close", counter) => goto(Closed) using(counter + 1) replying(Publish("dest", DoorMoved(counter + 1)))
      }
  
      whenUnhandled {
        case Event(cmd, counter) => {
          stay replying(Publish("dest", DoorNotMoved("cannot %s door in state %s" format (cmd, stateName))))
        }
      }
    }

To event-source a `Door`, it must be managed inside a `Component`.

    val journalDir = new File("target/example")
    val journal = system.actorOf(Props(new Journal(journalDir)))

    val destination: ActorRef = … 

    val doorComponent = Component(1, journal)
      .addDefaultOutputChannelToActor("dest", destination)
      .setProcessor(system.actorOf(Props[Door]))
    
    doorComponent.init()

The [`destination`](#destination) is the same as in section [First steps](#first-steps) which is an actor that writes received events to `stdout`. The `doorComponent` is now ready to use.

    doorComponent.inputProducer ! "open"
    doorComponent.inputProducer ! "close"

writes

    received event DoorMoved(1)
    received event DoorMoved(2)

to `stdout`. When you exit the application and run it again, the door state and the counter of the state machine are recovered and the next two output events will have higher move counts.

    received event DoorMoved(3)
    received event DoorMoved(4)

The complete source code is at [FsmExample.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/example/FsmExample.scala). It can be executed during a normal test run with `sbt test`.

Further features
----------------

- Components can be connected to directed graphs (containing cycles if needed) where a component graph can also be distributed (via [actor remoting](http://doc.akka.io/docs/akka/2.0.2/scala/remoting.html))
- Journals can be (synchronously or asynchronously) replicated to one or more slave nodes (experimental, see also [ReplicationSpec](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/core/ReplicationSpec.scala)).
- Components can be replicated by event-sourcing them on one or more slave nodes (experimental, see also [ReplicationSpec](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/core/ReplicationSpec.scala))
- Input and output channels maintain the order of event messages. Reliable output channels even maintain it in presence of destination failures. 
- Should applications want to re-sequence event messages based on application-defined sequence numbers, they can do so with a [Sequencer](https://github.com/eligosource/eventsourced/blob/master/src/main/scala/org/eligosource/eventsourced/core/Sequencer.scala) which is part of the library.
- … 

Reference application
---------------------

A reference web application, demonstrating event-sourcing best practices and usage of the Eventsourced library, is available [here](https://github.com/eligosource/eventsourced-example).

Current status
--------------

The documentation on this page only scratches the surface of what can be done with the library. More examples, a detailed reference documentation and an architecture description will follow.

The development status of the library is still early-access and we expect to have a first release ready in Sep or Oct 2012 (after having implemented some [planned enhancements](https://github.com/eligosource/eventsourced/issues?labels=enhancement)). 

The library isn't performance-optimized yet. Although we already measured throughputs in the order of 10k msgs/s (including persistence) and latencies around 1 ms for the examples above on a 2010 MacBook Pro with default JVM settings, there's enough room for performance improvements.

Forum
-----

[Eventsourced](http://groups.google.com/group/eventsourced) Google Group