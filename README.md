[![Build Status](https://secure.travis-ci.org/eligosource/eventsourced.png)](http://travis-ci.org/eligosource/eventsourced)

**Note:** This branch is based on Akka 2.1.0-RC2 and Scala 2.10.0-RC2. For an Akka 2.0.3 and Scala 2.9.2 based version switch to the [master](https://github.com/eligosource/eventsourced/tree/master/) branch.

Eventsourced
============

Introduction
------------

*Eventsourced* is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors. It appends event messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Persisting changes instead of current state also serves as a foundation to automatically adjust actor state to cope with retroactive changes.

Events produced by an event-sourced actor are sent to destinations via one or more channels. Channels connect an event-sourced actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery (e.g. after a crash or during normal application start), channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

Applications may connect event-sourced actors (via channels) to arbitrary complex event-sourced actor networks that can be consistently recovered by the library. Here, channels play an another important role during recovery as they ensure that replayed event messages do not wrongly interleave with new event messages created and sent by event-sourced actors. This ensures a consistent ordering of events during both, recovery and normal operation. Based on these mechanisms, for example, the implementation of reliable, long-running business processes using event-sourced state machines becomes almost trivial.

The library itself is an [Akka etxension](http://doc.akka.io/docs/akka/2.0.3/scala/extending-akka.html) and provides [stackable traits](http://www.artima.com/scalazine/articles/stackable_trait_pattern.html) to add event-sourcing capabilities to actors. All message exchanges performed by the library are asynchronous and non-blocking. Message delivery semantics are *at-least-once* which essentially requires [idempotent](http://queue.acm.org/detail.cfm?id=2187821) event message receivers. The library provides means to make event message receivers idempotent based on message sequence numbers or sender message ids.

### Application

The library doesn't impose any restrictions on the structure and semantics of application-level events. Therefore, applications may use the library for command-sourcing as well. The term *event* is mainly used to refer to application state changes. The [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) even demonstrates how both approaches (i.e. event-sourcing and command-sourcing) can be combined. It further demonstrates that the library fits well into applications that implement the [CQRS](http://martinfowler.com/bliki/CQRS.html) pattern and follow a [domain-driven design](http://domaindrivendesign.org/resources/what_is_ddd) (DDD). On the other hand, the library doesn't force applications to do so and allows them to implement event-sourcing (or command-sourcing) without CQRS and/or DDD.

### Journals

For persisting event messages, *Eventsourced* currently provides the following journal implementations:

- [`LeveldbJournal`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.LeveldbJournal$), a [LevelDB](http://code.google.com/p/leveldb/) and [leveldbjni](https://github.com/fusesource/leveldbjni) based journal which is currently recommended for production use. It comes with two different optimizations which are further explained in the [API docs](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.LeveldbJournal$) (see methods `processorStructured` and `sequenceStructured`). It will also be used in the following examples. Because LevelDB is a native library, this journal requires a special project configuration as explained in section [Installation](#installation). 
- [`JournalioJournal`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.JournalioJournal$), a [Journal.IO](https://github.com/sbtourist/Journal.IO) based journal. 
- [`InmemJournal`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.JournalioJournal$), an in-memory journal for testing purposes.

Further journal implementations are planned, including replicated and horizontally scalable journals (based on [Apache BookKeeper](http://zookeeper.apache.org/bookkeeper/) or [Redis](http://redis.io/), for example). Also planned for the near future is a journal plugin API and an event archive.  

### Resources

- [Eventsourced API](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.package)
- [Eventsourced reference application](https://github.com/eligosource/eventsourced-example)

### Support

- Community: [Eventsourced forum](http://groups.google.com/group/eventsourced)
- Commercial: [Eligotech B.V.](http://www.eligotech.com/)

Installation
------------

See [Installation](https://github.com/eligosource/eventsourced/wiki/Installation) Wiki page.

First steps
-----------

This section guides through the minimum steps required to create, use and recover an event-sourced actor and demonstrates the use of channels. Code from this section is contained in [FirstSteps.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/guide/FirstSteps.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.guide.FirstSteps'` (click [here](https://github.com/eligosource/eventsourced/wiki/Installation) for details about the `run-nobootcp` task). The legend to the figures used in this and other sections is in [Appendix A](#appendix-a-legend).

### Step 1: `EventsourcingExtension` initialization

[`EventsourcingExtension`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.EventsourcingExtension) is an Akka extension provided by the *Eventsourced* library. It is used by applications

- as factory of event-sourced actors (called *processors* or *event processors*)
- as factory of channels
- as registry for processors and channels
- to recover registered processors and channels from journaled event messages

An `EventsourcingExtension` is initialized with an `ActorSystem` and a journal `ActorRef`.

    import java.io.File
    import akka.actor._
    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal._

    val system: ActorSystem = ActorSystem("example")
    val journal: ActorRef = LeveldbJournal(new File("target/example-1"))
    val extension: EventsourcingExtension = EventsourcingExtension(system, journal)

This example uses a [LevelDB](http://code.google.com/p/leveldb/) based journal but any other [journal implementation](#journals) can be used as well.

### Step 2: Event-sourced actor definition

Event-sourced actors can be defined as 'plain' actors i.e. they don't need to care about appending received event messages to a journal. For example,

    class Processor extends Actor {
      var counter = 0

      def receive = {
        case msg: Message => {
          counter = counter + 1
          println("[processor] event = %s (%d)" format (msg.event, counter))
        }
      }
    }

is an actor that counts the number of received event [`Message`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Message)s. In *Eventsourced* applications, events are always communicated (transported) via event `Message`s.

### Step 3: Event-sourced actor creation and recovery

To make `Processor` an event-sourced actor, it must be modified with the stackable [`Eventsourced`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Eventsourced) trait during instantiation. 

    // create and register event-sourced processor
    val processor: ActorRef = extension.processorOf(Props(new Processor with Eventsourced { val id = 1 } ))

    // recover registered processors by replaying journaled events
    extension.recover()

An actor that is modified with `Eventsourced` journals event `Message`s before its `receive` method is called. The `extension.processorOf` method registers that actor under a unique `id`. The processor `id` is defined by implementing the abstract `Eventsourced.id` member which must be a positive integer. The `extension.recover` method recovers the state of `processor` by replaying all event messages that `processor` received (and journaled) in previous application runs. 

### Step 4: Event-sourced actor usage

The event-sourced `processor` can be used like any other actor. Messages of type [`Message`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Message) are written to `journal`, messages of any other type are directly received by `processor` without being journaled.

![Event-sourced actor](https://raw.github.com/eligosource/eventsourced/master/doc/images/firststeps-1.png)

    // send event message to processor (will be journaled)
    processor ! Message("foo")

A first application application run will create an empty journal. Hence, no event messages will be replayed and the `processor` writes

    [processor] event = foo (1)

to `stdout`. When the application is restarted, however, the `processor`'s state will be recovered by replaying the previously journaled event message. Then, the application sends another event message. You will therefore see

    [processor] event = foo (1)
    [processor] event = foo (2)

on `stdout` where the first `println` is triggered by a replayed event message. 

### Step 5: Channel usage

![Channel](https://raw.github.com/eligosource/eventsourced/master/doc/images/firststeps-2.png)

In this step, the event-sourced `processor` is extended to send out new event messages to a `destination`. It creates a another event message (by making a copy of the received event message) with an updated `event` field and sends the updated message to `destination`.

    class Processor(destination: ActorRef) extends Actor {
      var counter = 0;
  
      def receive = {
        case msg: Message => {
          counter = counter + 1
          // … 
          destination ! msg.copy(event = "processed %d event messages so far" format counter)
        }
      }
    }

Without any further steps, this would also send event messages to `destination` during recovery (i.e. during replay of event messages). With every application restart, `destination` would redundantly receive the whole event history again and again. This is not acceptable in most situations, such as when `destination` represents an external service, for example. 

To prevent redundant message deliveries to `destination` we need something that *remembers* which messages have already been sent. This is exactly the use case for [channels](#channels). We wrap the destination by a channel and let the processor communicate with the destination via that channel.

    val destination: ActorRef = system.actorOf(Props[Destination])
    // wrap destination by channel
    val channel: ActorRef = extension.channelOf(DefaultChannelProps(1, destination))
    // instantiate processor by passing the channel (i.e. wrapped destination) as constructor argument
    val processor: ActorRef = extension.processorOf(Props(new Processor(channel) with Eventsourced { val id = 1 } ))

A channel drops (ignores) all messages that have already been successfully delivered to the destination. It must also have a unique id (a positive integer which is `1` in our example). [`DefaultChannelProps`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.DefaultChannelProps) is a configuration object for a [`DefaultChannel`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.DefaultChannel). If applications need reliable event message delivery to destinations, they should use a [`ReliableChannel`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.ReliableChannel) that is configured with a [`ReliableChannelProps`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.ReliableChannelProps) configuration object.

Assuming the following definition of a `Destination` actor and that we're starting again from an empty journal

    class Destination extends Actor {
      def receive = {
        case msg: Message => {
          println("[destination] event = '%s'" format msg.event)
          // confirm receipt of event message from channel
          msg.confirm()
        }
      }
    }

you will see 

    [processor] event = foo (1)
    [destination] event = 'processed 1 event messages so far'

on `stdout` during a first application run. When running the application again, you will see that the event-sourced `processor` receives the complete event message history but the `destination` only receives the last event message produced by `processor` (which corresponds the the single event message sent to `processor` in the current application run):

    [processor] event = foo (1)
    [processor] event = foo (2)
    [destination] event = 'processed 2 event messages so far'

When receiving event messages from a channel, destinations must confirm the receipt of that message by calling `Message.confirm()` which asynchronously writes a confirmation (an *acknowledgement*) to the journal that the message has been successfully delivered. Later, you'll also see how destination implementors can add confirmation functionality with the stackable [`Confirm`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Confirm) trait.

This [First steps](#first-steps) guide is a rather low-level introduction to the *Eventsourced* library. More advanced library features will be presented in the following sections.

Stackable traits
----------------

### `Eventsourced`

![Eventsourced](https://raw.github.com/eligosource/eventsourced/master/doc/images/stackabletraits-1.png)

The [`Eventsourced`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Eventsourced) trait has already been discussed in section [First steps](#first-steps). It can be combined with the stackable `Receiver`, `Emitter` and/or `Confirm` traits where the `Eventsourced` trait must always the last modification i.e. 

    new MyActor with Receiver with Confirm with Eventsourced

### `Receiver`

![Receiver](https://raw.github.com/eligosource/eventsourced/master/doc/images/stackabletraits-2.png)

An actor that receives event [`Message`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Message)s often wants to pattern-match against the contained `event` directly instead of the whole event message. This can be achieved by modifying it with the [`Receiver`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Receiver) trait during instantiation.

    class MyActor extends Actor {
      def receive = {
        case event => println("received event %s" format event)
      }
    }
  
    val myActor = system.actorOf(Props(new MyActor with Receiver))
  
    myActor ! Message("foo")

In the above example, sending `Message("foo")` to `myActor` will write `received event foo` to `stdout`. The `Receiver` trait stores the received event message as *current* event message in a field, extracts the contained `event` from that message and calls the `receive` method of `MyActor` with `event` as argument. If `MyActor` wants to have access to the current event message it must be defined with a `Receiver` self-type and call the `Receiver.message` method.

    class MyActor extends Actor { this: Receiver =>
      def receive = {
        case event => {
           // obtain current event message
          val currentMessage = message
          // … 
          println("received event %s" format event)
        }
      }
    }

The `Receiver` trait can also be combined with the stackable `Eventsourced` and/or `Confirm` traits where `Receiver` must always be the first modification. For example:

    new MyActor with Receiver with Confirm with Eventsourced

Refer to the [API docs](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Receiver) for further details.

### `Emitter`

![Emitter](https://raw.github.com/eligosource/eventsourced/master/doc/images/stackabletraits-3.png)

Where a `Receiver` modification allows actors to pattern-match against incoming events directly instead of whole event `Message`s, an [`Emitter`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Emitter) introduces a corresponding simplification on the sending (outgoing) side. It allows actors to send (*emit*) events to channels without having to deal with whole event `Message`s. An emitter can also lookup channels by name.

    class MyActor extends Actor { this: Emitter =>
        def receive = {
          case event => {
            // emit event to channel "myChannel"
            emitter("myChannel") sendEvent ("received: %s" format event)
          }
        }
      }

    // create register channel under name "myChannel"
    extension.channelOf(DefaultChannelProps(1, destination).withName("myChannel"))
  
    val myActor = system.actorOf(Props(new MyActor with Emitter))

Event messages sent by an emitter to a channel are always derived from (i.e. are a copy of) the current event message (an `Emitter` is also `Receiver` and maintains a *current* event message, see also section [Receiver](#receiver)). A call to the `Emitter.emitter` method with a channel name as argument creates a [`MessageEmitter`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.MessageEmitter) object that captures the named channel and the current event message. Calling `sendEvent` on that object modifies the captured event message with the specified event argument and sends the updated event message to the channel. A `MessageEmitter` object can also be sent to other actors (or threads) and used there i.e. a `MessageEmitter` object is thread-safe. 

The `Emitter` trait can also be combined with the stackable `Eventsourced` and/or `Confirm` traits where `Emitter` must always be the first modification. For example:

    new MyActor with Emitter with Receive with Eventsourced 

Refer to the [API docs](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Emitter) for further details.

### `Confirm`

![Confirm](https://raw.github.com/eligosource/eventsourced/master/doc/images/stackabletraits-4.png)

The receipt of event messages from channels must be confirmed by calling `confirm()` or `confirm(true)` on the received event `Message`. Applications can also *negatively* confirm an event message receipt by calling `confirm(false)`. This, for example, causes a reliable channel to redeliver the event message.

Instead of calling `confirm(true)` or `confirm(false)` directly, actors can also be modified with the stackable [`Confirm`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Confirm) trait. This trait calls `confirm(true)` on the received event message when the modified actor's `receive` method returns normally and `confirm(false)` when it throws an exception.

This trait can either be used standalone

    new MyActor with Confirm

or in combination with the stackable `Receiver`, `Emitter` and/or `Eventsourced` traits where the `Confirm` modification must be made after a `Receiver` or `Emitter` modification but before an `Eventsourced` modification. For example:

    new MyActor with Receiver with Confirm with Eventsourced

Refer to the [API docs](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Confirm) for further details.

### Modified example

![Example](https://raw.github.com/eligosource/eventsourced/master/doc/images/stackabletraits-5.png)

This section modifies (and simplifies) the example from section [First steps](#first-steps) by making use of the stackable traits `Receiver`, `Emitter` and `Confirm`. In particular

- `Processor` will be modified with `Emitter` (in addition to `Eventsourced`)
- `Destination` will be modified with `Receiver` and `Confirm`

Code from this section is contained in [StackableTraits.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/guide/StackableTraits.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.guide.StackableTraits'`.

The new definition of `Processor`

    class Processor extends Actor { this: Emitter =>
      var counter = 0
  
      def receive = {
        case event => {
          counter = counter + 1
          println("[processor] event = %s (%d)" format (event, counter))
          emitter("destination") sendEvent ("processed %d events so far" format counter)
        }
      }
    }

has a self-type `Emitter`, pattern-matches against events directly and instead of passing the channel via the constructor it is now looked-up by name (`"destination"`). The channel name is specified during channel creation.

    extension.channelOf(DefaultChannelProps(1, destination).withName("destination"))

`Processor` must be instantiated with an additional `Emitter` modification to conform to the `Processor` self-type.

    val processor: ActorRef = extension.processorOf(Props(new Processor with Emitter with Eventsourced { val id = 1 } ))

The new definition of `Destination`

    class Destination extends Actor {
      def receive = {
        case event => {
          println("[destination] event = '%s'" format event)
        }
      }
    }

pattern-matches against events directly and leaves event message receipt confirmation to the `Confirm` trait. `Destination` must be instantiated with a `Receiver` and a `Confirm` modification.

    val destination: ActorRef = system.actorOf(Props(new Destination with Receiver with Confirm))

Sender references
-----------------

The *Eventsourced* library preserves sender actor references (accessible via the `sender` member field in `Actor`) for all

- message exchanges with actors that are modified with `Eventsourced`, `Receiver`, `Emitter` and/or `Conform` and
- message exchanges with destinations via channels (with some limitations for reliable channels, as described below)

i.e. there's no difference in sender reference usage between event-sourced actor applications and plain actor applications. If you know how sender references work in Akka [actors](http://doc.akka.io/docs/akka/snapshot/scala/actors.html), the following will sound familiar to you.

![Processor reply](https://raw.github.com/eligosource/eventsourced/master/doc/images/senderrefs-1.png)

For example, taking the code from section [First steps](#first-steps) as a starting point, `Processor` can be extended to reply to message senders as follows.

    class Processor(destination: ActorRef) extends Actor {
      // …

      def receive = {
        case msg: Message => {
          // …
          // reply to sender
          sender ! ("done processing event = %s" format msg.event)
        }
      }
    }

    
Applications can now *ask* the `processor` and will get a response asynchronously.

    processor ? Message("foo") onSuccess {
      case response => println(response)
    }

No surprise here. The sender reference in this example represents the future that is returned from the `?` method call. But what happens during a replay? During a replay, the sender reference will be `deadLetters` because the library doesn't store sender references in the journal. That's a sensible default because most sender references won't exist any more after application restart (and hence during a replay). This is especially true for (short-lived) futures.

![Destination reply](https://raw.github.com/eligosource/eventsourced/master/doc/images/senderrefs-2.png)

Instead of replying to the sender, the processor can also forward the sender reference to a destination and let the destination reply to the sender. This even works if the destination is wrapped by a channel because a channel simply forwards sender references when delivering event messages to destinations.

    class Processor(destination: ActorRef) extends Actor {
      var counter = 0

      def receive = {
        case msg: Message => {
          // …
          // forward modified event message to destination (together with sender reference)
          destination forward msg.copy(event = "processed %d event messages so far" format counter)
        }
      }
    }
  
    // channel destination
    class Destination extends Actor {
      def receive = {
        case msg: Message => {
          // … 
          // reply to sender
          sender ! ("done processing event = %s (%d)" format msg.event)
        }
      }
    }

Again, no surprise here. The situation is a bit more tricky when using reliable channels. They forward sender references only after their activation with `extension.recover()`. This can be disregarded by most applications because they will anyway run recovery before using channels for event message delivery. If a reliable channel reaches the maximum number of redelivery attempts (in case of repeated destination failures), it restarts itself and drops the sender references for all queued event messages. After restart, it continues to preserve the sender references for newly enqueued event messages. This can also be disregarded by most applications because preserving sender references makes only sense for destinations that do not fail for a longer period of time, otherwise they anyway won't be able to reply within a certain timeout. More on that in the API docs for [`ReliableChannel`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.ReliableChannel) and [`RedeliveryPolicy`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.RedeliveryPolicy).

When using a [`MessageEmitter`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.MessageEmitter) for sending event messages (see also section [Emitter](#emitter)) applications can choose between methods `sendEvent` and `forwardEvent` where `sendEvent` takes an implicit sender reference as parameter and `forwardEvent` forwards the current sender reference. They work in the same way as the `!` and `forward` methods on `ActorRef`, respectively.

Code from this section is contained in [SenderReferences.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/guide/SenderReferences.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.guide.SenderReferences'`.

Channels
--------

A channel is an actor that keeps track of successfully delivered event messages. Channels are used by event-sourced actors (processors) to prevent redundant message delivery to destinations during event message replay. See also section [External Updates](http://martinfowler.com/eaaDev/EventSourcing.html#ExternalUpdates) in Martin Fowler's [Event Sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) article as well as section [Channel usage](#step-5-channel-usage) in the [First steps](#first-steps) guide for an example. Channels need not be used by event-sourced processors if the event message destination was received via a [sender reference](#sender-references). Sender references are always the `deadLetters` reference during a replay. 

Currently, the library provides two different channel implementations: [`DefaultChannel`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.DefaultChannel) and [`ReliableChannel`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.ReliableChannel) which are explained in the following two subsections.

### `DefaultChannel`

![Default channel](https://raw.github.com/eligosource/eventsourced/master/doc/images/channels-1.png)

A default channel is a transient channel that delivers event messages to a destination actor. When the destination confirms the delivery of an event message by calling either `confirm()` or `confirm(true)` on the received `Message` object, a confirmation (an *acknowledgement*) is asynchronously written to the journal. During a replay, event messages for which a confirmation exists won't be delivered again to the destination. 

Event messages that are negatively confirmed by the destination (via a call to `confirm(false)` on the received event message) will be re-delivered during the next event message replay (e.g. via `extension.recover()`). This is also the case for event messages for which no confirmation has been made. Therefore, the order of event messages received by a destination from a default channel may differ from the order of event messages generated by an event-sourced processor in the case of destination failures. 

A `DefaultChannel` is created and registered at an `EventsourcingExtension` as follows.

    val extension: EventsourcingExtension = … 
    val destination: ActorRef = … 
    val channelId: Int = … 

    val channel: ActorRef = extension.channelOf(DefaultChannelProps(channelId, destination))

The `channelId` must be a positive integer. The map of registered channels can be obtained via `extension.channels` which returns a map of type Map[Int, ActorRef] where the mapping key is the channel id. Channels can optionally be registered under a custom name as well (see also section [Emitter](#emitter)).

    // … 
    val channelId: Int = … 
    val channelName: String = … 

    val channel: ActorRef = extension.channelOf(DefaultChannelProps(channelId, destination).withName(channelName))

The map of registered named channels can be obtained via `extension.namedChannels` which returns a map of type Map[String, ActorRef] where the mapping key is the channel name.

### `ReliableChannel`

![Reliable channel](https://raw.github.com/eligosource/eventsourced/master/doc/images/channels-2.png)

A reliable channel is a persistent channel that writes event messages to a journal before delivering them to a destination actor. In contrast to a default channel, a reliable channel preserves the order of messages as generated by an event-sourced processor and attempts to re-deliver event messages on destination failures. Therefore, a reliable channel enables applications to recover from temporary destination failures without having to run an event message replay. 

If a destination positively confirms an event message delivery, the stored event message is removed from the channel and the next one is delivered (if there is one). If a destination negatively confirms an event message delivery or a confirmation timeout occurs, the channel makes a re-delivery attempt. If the destination continues to fail (by making negative confirmations or no confirmations at all), further re-deliveries are made until a maximum number of re-deliveries is reached. In this case, the channel restarts itself and, after a certain recovery delay, re-delivery is starting again. Refer to the [`ReliableChannel`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.ReliableChannel) API docs for details.

A `ReliableChannel` is created and registered in the same way as a default channel except that a [`ReliableChannelProps`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.RedeliveryPolicy) configuration object is used. 

    // … 
    val channel: ActorRef = extension.channelOf(ReliableChannelProps(channelId, destination))

This configuration object additionally allows applications to configure a [`RedeliveryPolicy`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.RedeliveryPolicy) for the channel (not shown).

### Usage hints

For channels to work properly, event-sourced processors must copy the `processorId` and `sequenceNr` values from a received (and journaled) input event message to output event messages. This is usually done by calling `copy()` on the received input event message and updating only those fields that are relevant for the application such as `event` or `senderMessageId`, for example: 

    class Processor(channel: ActorRef) extends Actor {
      def receive = {
        case msg: Message => {
          // … 
          channel ! msg.copy(event = …, senderMessageId = …)
        }
      }
    }

When using a [message emitter](#emitter), this is done automatically.

Recovery
--------

Recovery is a procedure needed to re-create the state of event-sourced applications consisting of [`Eventsourced`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Eventsourced) actors (processors) and [channels](#channels). Recovery is usually done at application start, either after normal termination or after a crash. 

    val system: ActorSystem = … 
    val journal: ActorRef = … 

    val extension = EventsourcingExtension(system, journal)

    // create and register event-sourced processors
    extension.processorOf(…)
    // … 

    // create and register channels
    extension.channelOf(…)
    // … 

    // recover state of registered processors and activate channels
    extension.recover()

    // processors and channels are now ready to use
    // …

The `recover()` method first replays journaled event messages to all registered processors. By replaying the event message history, processors can recover state. Processors that emit event messages to one or more channels will also do so during replay. These channels will either ignore (discard) event messages that have already been successfully delivered (i.e. *acknowledged*) in previous application runs or buffer them for later delivery. 

If a channel delivered event messages immediately instead of buffering them, delivered event messages could wrongly interleave with replayed event messages. This could lead to inconsistencies in event message ordering across application runs and therefore to inconsistencies in application state. Therefore, recovery must ensure that buffered event messages are only delivered after all replayed event messages have been added to their corresponding processors' mailboxes. 

Delivery of buffered event messages is triggered by activating channels. The implementation of `recover()` activates registered channels immediately after having finished the replay. By ensuring that buffered event messages are only delivered after replayed event messages, consistency in event message ordering can be guaranteed. This is especially important for the recovery of processors and channels that are connected to a cyclic, directed graph.

The [`EventsourcingExtension`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.EventsourcingExtension) also supports event message replay for individual processors (refer to the API docs for details). This can be useful in situations where processors are registered at `extension` after an initial recovery.

    // initial recovery
    extension.recover()

    val processorId: Int = …  

    // register another processor after initial recovery
    extension.processorOf(ProcessorProps(processorId, …))

    // replay event messages for that processor individually
    extension.replay(id => if (id == processorId) Some(0) else None)

A call to `replay` can be omitted if a processor did not journal any event message in previous application runs. Channels can be activated individually with the `deliver(channelId: Int)` method. Whatever `EventsourcingExtension` methods applications use to recover state of event-sourced applications, they must ensure that processor ids and channel ids are consistently used across application runs.

### Awaiting completion

The `replay` and `recover` methods do *not* wait for replayed event messages being processed by the corresponding processors. However, any new message sent to any registered processor, after `replay` or `recover` returned, is guaranteed to be processed after the replayed event messages. Applications that want to wait for processors to complete processing of replayed event messages, should use the `awaitProcessorCompletion()` method of [`EventsourcingExtension`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Eventsourced). 

    val extension: EventsourcingExtension = … 

    extension.recover()
    extension.awaitProcessorCompletion()

For example, this is useful in situations where event-sourced processors maintain state via STM references and the application wants to ensure that the (externally visible) state is fully recovered before accepting new read requests from client applications. By default, the `awaitProcessorCompletion()` method waits for all registered processors to complete but applications can also specify a subset of registered processors.

### State dependencies

The behavior of [`Eventsourced`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Eventsourced) processors may depend on the state of other `Eventsourced` processors. For example processor A sends a message to processor B and processor B replies with a message that includes (part of) processor B's state. Depending on the state value included in the reply, processor A may take different actions. To ensure a proper recovery of such a setup, any state-conveying or state-dependent messages exchanged between processors A and B must be of type [`Message`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Message) (see also [DependentStateRecoverySpec.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/core/DependentStateRecoverySpec.scala)). Exchanging state via non-journaled messages (i.e. messages of type other than `Message`) can break consistent recovery. This is also the case if an `Eventsourced` processor maintains state via an externally visible STM reference and another `Eventsourced` processor directly reads from that reference.

Behavior changes
----------------

Actors modified with a stackable `Receiver`, `Emitter` and/or `Eventsourced` modification have two options to change their behavior: either with `context.become()` (and `context.unbecome()`) or with `become()` (and `unbecome()`).  

1. An actor that changes its behavior with `context.become()` will loose the functionality introduced by a stackable `Receiver`, `Emitter` and/or `Eventsourced` modification.
2. An actor that changes its behavior with `become()` will keep the functionality introduced by a stackable `Receiver`, `Emitter` and/or `Eventsourced` modification. 

For example, an actor that is modified with `Eventsourced` and that changes its behavior with `become()` will continue to journal event messages. An actor that changes its behavior with `context.become()` will stop journaling event messages (although a `context.unbecome()` can revert that).  

The methods `become()` and `unbecome()` are defined on the [`Behavior`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Behavior) trait from which `Receiver`, `Emitter` and `Eventsourced` inherit.

Event series
------------

When a processor derives more than one output event message from a single input event message and emits those output messages to a single channel, it generates a series of event messages. For an event message series, the event processor should set the `ack` field for all but the last emitted message to `false`. 

    class Processor(channel: ActorRef) extends Actor {
      def receive = {
        case msg: Message => {
          // … 
          channel ! msg.copy(event = "event 1", ack = false) // 1st message of series
          channel ! msg.copy(event = "event 2", ack = false) // 2nd message of series
          // … 
          channel ! msg.copy(event = "event n") // last message of series
        }
      }
    }

Processors that use an emitter do that in the following way.

    class Processor extends Actor { this: Emitter =>
      def receive = {
        case event => {
          // … 
          emitter("channelName") send (msg => msg.copy(event = "event 1", ack = false)) // 1st message of series
          emitter("channelName") send (msg => msg.copy(event = "event 2", ack = false)) // 2nd message of series
          // … 
          emitter("channelName") sendEvent "event n"
        }
      }
    }

This ensures that an acknowledgement is only written to the journal after the last event message of a series has either been successfully delivered by a [default channel](#defaultchannel) or stored by a [reliable channel](#reliablechannel). Destinations, however, should confirm the receipt of every event message, regardless whether it belongs to a series or not.

Idempotency
-----------

Under certain failure conditions, [channels](#channels) may deliver event messages to destinations more than once. A typical example is that a destination positively confirms a message receipt but the application crashes shortly before that confirmation is written to the journal. In this case, the destination will receive the event message again during recovery. 

For these (but also other) reasons, channel destinations must be idempotent event message consumers which is an application-level concern. For example, an event message consumer that stores received purchase order objects in a map (where the map key is the order id) is likely to be an idempotent consumer because receiving a purchase order only once or several times will lead to the same result: the purchase order is contained in the map only once. An event message consumer that counts the number of received purchase orders is not an idempotent consumer: a re-delivery will lead to a wrong counter value from a business logic perspective. In this case the event message consumer must implement some extra means to detect event message *duplicates*. 

For detecting duplicates, applications can use the `senderMessageId` and `sequenceNr` fields of event [`Message`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Message)s. A sequence number (`sequenceNr`) is assigned to an event message when it is written to a journal i.e. before it is received by an `Eventsourced` processor or after it has been added to a reliable channel. The `senderMessageId` is an optional `String` that is used on application-level only (i.e. it is neither changed nor interpreted by the library). Therefore, event messages that are re-delivered by a channel are guaranteed to have the same `senderMessageId`. Consumers that keep track of `senderMessageId` values can therefore detect duplicates. What follows are some general guidelines for implementing idempotent event message processing based on these two `Message` fields. 

- `Eventsourced` processors can encode the message sequence number (`sequenceNr`) in the `senderMessageId` field to allow downstream event message consumers to detect duplicates. Encoding the sequence number has the advantage that downstream consumers only need to remember the last consumed `senderMessageId`: if the `senderMessageId` of a newly received event message encodes a sequence number that is less than or equal to the one encoded in the last consumed `senderMessageId` then the newly received event message is a duplicate and can/should be ignored by the consumer.
- `Eventsourced` processors that emit an [event message series](#event-series) should encode both, the sequence number and an output message index, in the `senderMessageId` field. Consumers should then compare encoded sequence number - index pairs for detecting duplicates. 
- Consumers that are `Eventsourced` processors can store the last consumed `senderMessageId` as part of their state which will be recovered during an event message replay. Other consumers must store the `senderMessageId` somewhere else.

Further examples
----------------

### Order management

The order management example in this section is taken from [Martin Fowler](http://www.martinfowler.com/)'s great [LMAX article](http://martinfowler.com/articles/lmax.html):

> Imagine you are making an order for jelly beans by credit card. A simple retailing system would take your order information, use a credit card validation service to check your credit card number, and then confirm your order - all within a single operation. The thread processing your order would block while waiting for the credit card to be checked, but that block wouldn't be very long for the user, and the server can always run another thread on the processor while it's waiting.
>
> In the LMAX architecture, you would split this operation into two. The first operation would capture the order information and finish by outputting an event (credit card validation requested) to the credit card company. The Business Logic Processor would then carry on processing events for other customers until it received a credit-card-validated event in its input event stream. On processing that event it would carry out the confirmation tasks for that order.

This can be implemented with the *Eventsourced* library as shown in the following diagram (legend is in [Appendix A](#appendix-a-legend)).

![Order management](https://raw.github.com/eligosource/eventsourced/master/doc/images/ordermgnt-1.png)

- We implement the mentioned *Business Logic Processor* processor as event-sourced actor (`OrderProcessor`). It processes `OrderSubmitted` events by assigning submitted orders an id and storing them in a map (= state of `OrderProcessor`). For every submitted order it emits a `CreditCardValidationRequested` event.
- `CreditCardValidationRequested` events are processed by a `CreditCardValidator` actor. It contacts an external credit card validation service and sends `CreditCardValidated` events back to the `OrderProcessor` for every order with a valid credit card number. In the example implementation below, we won't actually use an external service to keep the implementation simple, but for real-world implementations, [akka-camel](http://doc.akka.io/docs/akka/snapshot/scala/camel.html) would be a perfect fit here.
- On receiving a `CreditCardValidated` event, the event-sourced `OrderProcessor` updates the status of corresponding order to `validated = true` and sends an `OrderAccepted` event, containing the updated order, to `Destination`. It also replies the updated order to the initial sender.

The `Order` domain object, the domain events and the `OrderProcessor` are defined as follows:

    // domain object
    case class Order(id: Int = -1, details: String, validated: Boolean = false, creditCardNumber: String)
  
    // domain events
    case class OrderSubmitted(order: Order)
    case class OrderAccepted(order: Order)
    case class CreditCardValidationRequested(order: Order)
    case class CreditCardValidated(orderId: Int)
  
    // event-sourced order processor
    class OrderProcessor extends Actor { this: Emitter =>
      var orders = Map.empty[Int, Order] // processor state
  
      def receive = {
        case OrderSubmitted(order) => {
          val id = orders.size
          val upd = order.copy(id = id)
          orders = orders + (id -> upd)
          emitter("validation requests") forwardEvent CreditCardValidationRequested(upd)
        }
        case CreditCardValidated(orderId) => {
          orders.get(orderId).foreach { order =>
            val upd = order.copy(validated = true)
            orders = orders + (orderId -> upd)
            sender ! upd
            emitter("accepted orders") sendEvent OrderAccepted(upd)
          }
        }
      }
    }

The `OrderProcessor` uses a message `emitter` to send `CreditCardValidationRequested` events to `CreditCardValidator` via the named `"validation requests"` channel. The `forwardEvent` method not only sends these event but also forwards the initial [sender reference](#sender-references). Upon receiving a `CreditCardValidationRequested` event, the `CreditCardValidator` runs a credit card validation in the background and sends a `CreditCardValidated` event back to the `OrderProcessor`

    class CreditCardValidator(orderProcessor: ActorRef) extends Actor { this: Receiver =>
      def receive = {
        case CreditCardValidationRequested(order) => {
          val sdr = sender  // initial sender
          val msg = message // current event message
          Future {
            // do some credit card validation
            // ...
  
            // and send back a successful validation result (preserving the initial sender)
            orderProcessor tell (msg.copy(event = CreditCardValidated(order.id)), sdr)
          }
        }
      }
    }


The `CreditCardValidator` again forwards the initial sender reference which finally enables the `OrderProcessor` to reply to the initial sender when it receives the `CreditCardValidated` event. The `OrderProcessor` also sends an `OrderAccepted` event to `Destination` via the named `"accepted orders"` channel. 

    class Destination extends Actor {
      def receive = {
        case event => println("received event %s" format event)
      }
    }

Next step is to wire the collaborators and to recover them:

    val extension: EventsourcingExtension = … 

    val processor = extension.processorOf(Props(new OrderProcessor with Emitter with Confirm with Eventsourced { val id = 1 }))
    val validator = system.actorOf(Props(new CreditCardValidator(processor) with Receiver))
    val destination = system.actorOf(Props(new Destination with Receiver with Confirm))

    extension.channelOf(ReliableChannelProps(1, validator).withName("validation requests"))
    extension.channelOf(DefaultChannelProps(2, destination).withName("accepted orders"))
   
    extension.recover()

The named `"validation requests"` channel is a reliable channel that re-delivers `CreditCardValidationRequested` events in case of `CreditCardValidator` failures (for example, when the external credit card validation service is temporarily unavailable). Furthermore, it should be noted that the `CreditCardValidator` does not confirm event message deliveries (it neither calls `confirm()` explicitly nor is it modified with the `Confirm` trait during instantiation). Delivery confirmation will take place when the `OrderProcessor` successfully processed the `CreditCardValidated` event. 

The `Order processor` is now ready to receive `OrderSubmitted` events.

    processor ? Message(OrderSubmitted(Order(details = "jelly beans", creditCardNumber = "1234-5678-1234-5678"))) onSuccess {
      case order: Order => println("received response %s" format order)
    }

Running this example with an empty journal will write

    received response Order(0,jelly beans,true,1234-5678-1234-5678)
    received event OrderAccepted(Order(0,jelly beans,true,1234-5678-1234-5678))

to `stdout`. You may observe a different line ordering when running the example. The submitted order was assigned an `id` of `0` which corresponds to the initial size of the `OrderProcessor`'s `orders` map. A second application run will first recover the previous application state, so that another order submission will generate an order `id` of `1`.

    received response Order(1,jelly beans,true,1234-5678-1234-5678)
    received event OrderAccepted(Order(1,jelly beans,true,1234-5678-1234-5678))

The example code is contained in [OrderExample.scala](https://github.com/eligosource/eventsourced/blob/master/src/test/scala/org/eligosource/eventsourced/example/OrderExample.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.example.OrderExample'`. 

### State machines

![State machines](https://raw.github.com/eligosource/eventsourced/wip-akka-2.1/doc/images/statemachines-1.png)

With a recent [change](https://www.assembla.com/spaces/akka/tickets/2680) in Akka 2.1, event-sourcing Akka [FSM](http://doc.akka.io/docs/akka/2.0.3/scala/fsm.html)s is now pretty easy. The following state machine example is a `Door` which can be in one of two states: `Open` and `Closed`. 

    sealed trait DoorState
  
    case object Open extends DoorState
    case object Closed extends DoorState
  
    case class DoorMoved(state: DoorState, times: Int)
    case class DoorNotMoved(state: DoorState, cmd: String)
    case class NotSupported(cmd: Any)
  
    class Door extends Actor with FSM[DoorState, Int] { this: Emitter =>
      startWith(Closed, 0)
  
      when(Closed) {
        case Event("open", counter) => {
          emit(DoorMoved(Open, counter + 1))
          goto(Open) using(counter + 1)
        }
      }
  
      when(Open) {
        case Event("close", counter) => {
          emit(DoorMoved(Closed, counter + 1))
          goto(Closed) using(counter + 1)
        }
      }
  
      whenUnhandled {
        case Event(cmd @ ("open" | "close"), counter) => {
          emit(DoorNotMoved(stateName, "cannot %s door" format cmd))
          stay
        }
        case Event(cmd, counter) => {
          emit(NotSupported(cmd))
          stay
        }
      }

      def emit(event: Any) = emitter("destination") forwardEvent event
    }

On state changes, a door emits `DoorMoved` events to the named `"destination"` channel. `DoorMoved` events contain the door's current state and the number of moves so far. On invalid attempts to move a door e.g. trying to open an opened door, a `DoorNotMoved` event is emitted. The channel destination is an actor that simply prints received events to `stdout`. 

    class Destination extends Actor {
      def receive = { case event => println("received event %s" format event) }
    }

After configuring the application

    val system: ActorSystem = … 
    val extension: EventsourcingExtension = …

    val destination = system.actorOf(Props(new Destination with Receiver with Confirm))

    extension.channelOf(DefaultChannelProps(1, destination).withName("destination"))
    extension.processorOf(Props(new Door with Emitter with Eventsourced { val id = 1 } ))
    extension.recover()

    val door = extension.processors(1)

we can start sending event messages to the `door`:

    door ! Message("open")
    door ! Message("close")
    
This will write 

    received event DoorMoved(Open,1)
    received event DoorMoved(Closed,2)

to `stdout`. When trying to attempt an invalid state change with

    door ! Message("close")

the `destination` will receive a `DoorNotMoved` event:

    received event DoorNotMoved(Closed,cannot close door)

Restarting the example application will recover the door's state so that sending of

    door ! Message("open")
    door ! Message("close")

will produce

    received event DoorMoved(Open,3)
    received event DoorMoved(Closed,4)

The code from this section is contained in slightly modified form in [FsmExample.scala](https://github.com/eligosource/eventsourced/blob/wip-akka-2.1/src/test/scala/org/eligosource/eventsourced/example/FsmExample.scala).

### Clustering

This section makes the `Door` state machine from the [previous example](#state-machines) highly-available in an Akka [cluster](http://doc.akka.io/docs/akka/2.1.0-RC1/cluster/index.html). The `Door` state machine is a cluster-wide singleton which is managed by `NodeActor`s. There's one `NodeActor` per cluster node listening to cluster events. If a `NodeActor` becomes the master (= leader) it creates and recovers a `Door` instance. The other `NodeActor`s obtain a remote reference to `Door` instance on master. 

![Clustering](https://raw.github.com/eligosource/eventsourced/wip-akka-2.1/doc/images/clustering-1.png)

Clients interact with the `Door` singleton via `NodeActor`s by sending them door commands (`"open"` or `"close"`). `NodeActor`s accept commands on any cluster node, not only on master. A `NodeActor` forwards these commands to the `Door` as command [`Message`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Message)s. Event `Message`s emitted by the `Door` are sent to a remote `Destination` actor via the named `"destination"` channel. The `Destination` creates a response from the received events and sends that response back to the initial sender. The application that runs the `Destination` actor is not part of the cluster but a standalone remote application. It also hosts the journal that is used by the cluster nodes (which is a SPOF in this example but later versions will use a distributed journal). 

When the master crashes, another node in the cluster becomes the master and recovers the `Door` state machine.

![Clustering](https://raw.github.com/eligosource/eventsourced/wip-akka-2.1/doc/images/clustering-2.png)

The code from this section is contained in [ClusterExample.scala](https://github.com/eligosource/eventsourced/blob/wip-akka-2.1/src/test/scala/org/eligosource/eventsourced/example/ClusterExample.scala), the configuration files used are [journal.conf](https://github.com/eligosource/eventsourced/blob/wip-akka-2.1/src/test/resources/journal.conf) and [cluster.conf](https://github.com/eligosource/eventsourced/blob/wip-akka-2.1/src/test/resources/cluster.conf). For a more detailed description of the example code, refer to the code comments. To run the distributed example application, first start the application that hosts the `Destination` actor (and the journal):

    sbt 'test:run-main org.eligosource.eventsourced.example.Journal'

Then start the first seed node of the cluster

    sbt 'test:run-main org.eligosource.eventsourced.example.Node 2561'

then the second seed node

    sbt 'test:run-main org.eligosource.eventsourced.example.Node 2562'

and finally a third cluster node

    sbt 'test:run-main org.eligosource.eventsourced.example.Node'

Most likely the first seed node will become the master which writes 

    MASTER: recovered door at akka://node@127.0.0.1:2561

to `stdout`. The other nodes become slaves that write

    SLAVE: referenced door at akka://node@127.0.0.1:2561

to `stdout`. All nodes prompt the user to enter a door command:

    command (open|close):

We will now enter commands on the last started cluster node (a slave node). 

The `Door` singleton is initially in closed state. Entering `open` will open it:

    command (open|close): open
    moved 1 times: door now open

Then close it again:

    command (open|close): close
    moved 2 times: door now closed

Trying to close a closed door will result in an error:

    command (open|close): close
    cannot close door: door is closed

Now kill the master node with `ctrl^c`. This will also destroy the `Door` singleton. After 1-2 seconds, a new master has been determined by the cluster. The new master is going to recover the event-sourced `Door` singleton. The slave will renew its remote reference to the `Door`. To verify that the `Door` has been properly recovered, open the door again:

    command (open|close): open
    moved 3 times: door now open

We can see that the `Door` state (which contains the number of past moves) has been properly failed-over to the new master node.

Miscellaneous
-------------

### Multicast processor

![Multicast](https://raw.github.com/eligosource/eventsourced/master/doc/images/multicast-1.png)

The [`Multicast`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.Multicast) processor is a predefined `Eventsourced` processor that forwards received event messages to multiple targets. Using a `Multicast` processor with n targets is an optimization of having n `Eventsourced` processors that receive the same event `Message`s. Using a multicast processor, a received event message is journaled only once whereas with n `Eventsourced` processors that message would be journaled n times (once for each processor). Using a `Multicast` processor for a large number of targets can therefore significantly save disk space and increase throughput. 

Applications can create a `Multicast` processor with the `multicast` factory method which is defined in package [`core`](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.core.package).

    // … 
    import org.eligosource.eventsourced.core._

    val extension: EventsourcingExtension = … 

    val processorId: Int = … 
    val target1: ActorRef = … 
    val target2: ActorRef = … 

    val multicast = extension.processorOf(Props(multicast(1, List(target1, target2))))

This is equivalent to 

    val multicast = extension.processorOf(Props(new Multicast(List(target1, target2), identity) with Eventsourced { val id = processorId } ))

Applications that want to modify received event `Message`s, before they are forwarded to targets, can specify a `transformer` function.

    val transformer: Message => Any = msg => msg.event
    val multicast = extension.processorOf(Props(multicast(1, List(target1, target2), transformer)))

In the above example, the `transformer` function extracts the `event` from a received event `Message`. If the `transformer` function is not specified, it defaults to the `identity` function. Another `Multicast` factory method is the `decorator` method for creating a multicast processor with a single target.

### Retroactive changes

TODO

### Snapshots

TODO

Appendix A: Legend
------------------

![Legend](https://raw.github.com/eligosource/eventsourced/master/doc/images/legend.png)
