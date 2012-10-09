[![Build Status](https://secure.travis-ci.org/eligosource/eventsourced.png)](http://travis-ci.org/eligosource/eventsourced)

- This user guide is work in progress …
- Old user guide is [here](https://github.com/eligosource/eventsourced/blob/master/README.md)

Eventsourced
============

Introduction
------------

<i>Eventsourced</i> is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors. It appends event messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Persisting changes instead of current state also serves as a foundation to automatically adjust actor state to cope with retroactive changes.

Events produced by an event-sourced actor are sent to destinations via one or more channels. Channels connect an event-sourced actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

Applications may connect event-sourced actors via channels to arbitrary complex event-sourced actor networks that can be consistently recovered by the library (e.g. after a crash or during normal application start). Channels play an important role during recovery as they ensure that replayed event messages do not wrongly interleave with new event messages created and sent by event-sourced actors. 

Based on these mechanisms, for example, the implementation of reliable, long-running business processes using event-sourced state machines becomes almost trivial. Here, applications may use Akka's [FSM](http://doc.akka.io/docs/akka/2.0.3/scala/fsm.html) (or just plain actors) to implement state machines where persistence and recovery is provided by the <i>Eventsourced</i> library.

The library itself is an [Akka etxension](http://doc.akka.io/docs/akka/2.0.3/scala/extending-akka.html) and provides [stackable traits](http://www.artima.com/scalazine/articles/stackable_trait_pattern.html) to add event-sourcing capabilities to actors. All message exchanges performed by the library are asynchronous and non-blocking. Message delivery semantics are <i>at-least-once</i> which essentially requires [idempotent](http://queue.acm.org/detail.cfm?id=2187821) event message receivers. The library provides means to make event message receivers idempotent based on message sequence numbers or sender message ids.

### Application

The library doesn't impose any restriction on the structure and semantics of application-level events. It uses the term <i>event</i> mainly to refer to application state changes. Consequently, applications may therefore use the library for command-sourcing as well. The [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) even demonstrates how both approaches (i.e. event-sourcing and command-sourcing) can be combined into a single application.

It further demonstrates that the library fits well into applications that implement the [CQRS](http://martinfowler.com/bliki/CQRS.html) pattern and follow a [domain-driven design](http://domaindrivendesign.org/resources/what_is_ddd) (DDD). On the other hand, the library doesn't force applications to do so and allows them to implement event-sourcing (or command-sourcing) without CQRS and/or DDD.

### Journals

For persisting event messages, <i>Eventsourced</i> currently provides the following journal implementations:

- [`LeveldbJournal`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.LeveldbJournal$), a [LevelDB](http://code.google.com/p/leveldb/) and [leveldbjni](https://github.com/fusesource/leveldbjni) based journal which is currently recommended for application development and operation. It comes with two different optimizations which are further explained in the [API docs](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.LeveldbJournal$) (see methods `processorStructured` and `sequenceStructured`). It will also be used in the following examples. Because LevelDB is a native library, this journal requires a special project configuration as explained in section [Installation](#installation). 
- [`JournalioJournal`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.JournalioJournal$), a [Journal.IO](https://github.com/sbtourist/Journal.IO) based journal. 
- [`InmemJournal`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.journal.JournalioJournal$), an in-memory journal for testing purposes.

Further journal implementations are planned, including distributed, highly-available and horizontally scalable journals (based on [Apache BookKeeper](http://zookeeper.apache.org/bookkeeper/) or [Redis](http://redis.io/), for example). Also planned for the near future is a journal plugin API and an event archive.

### Sponsor

This project is sponsored by [Eligotech B.V.](http://www.eligotech.com/)

### Resources

- [Eventsourced API](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.package)
- [Eventsourced reference application](https://github.com/eligosource/eventsourced-example) (work in progress ...)
- [Eventsourced forum](http://groups.google.com/group/eventsourced)


Installation
------------

See [Installation](https://github.com/eligosource/eventsourced/wiki/Installation) Wiki page.

First steps
-----------

This section guides through the minimum steps required to create, use and recover an event-sourced actor. Code from this section is contained in [FirstSteps.scala](https://github.com/eligosource/eventsourced/blob/wip-es-trait/src/test/scala/org/eligosource/eventsourced/guide/FirstSteps.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.guide.FirstSteps'` (click [here](https://github.com/eligosource/eventsourced/wiki/Installation) for details about the `run-nobootcp` task). The legend to the figures used in this and other sections is in [Appendix A](#appendix-a-legend).

### Step 1: `EventsourcingExtension` initialization

[`EventsourcingExtension`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.EventsourcingExtension) is an Akka extension provided by the <i>Eventsourced</i> library. It is used by applications

- as factory of event-sourced actors (called <i>processors</i> or <i>event processors</i>)
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
      var counter = 0;

      def receive = {
        case msg: Message => {
          counter = counter + 1
          println("received message #%d" format counter)
        }
      }
    }

is an actor that counts the number of received event [`Message`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Message)s. In <i>Eventsourced</i> applications, events are always communicated (transported) via event `Message`s.

### Step 3: Event-sourced actor creation and recovery

To make `Processor` an event-sourced actor, it is modified with the stackable [`Eventsourced`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Eventsourced) trait during instantiation. 

    // create and register event-sourced processor
    val processor: ActorRef = extension.processorOf(ProcessorProps(1, new Processor with Eventsourced))

    // recover registered processors by replaying journaled events
    extension.recover()

The `extension.processorOf` method registers that actor under a unique id given by the `ProcessorProps` configuration object and returns an `ActorRef` for the event-sourced actor. The `extension.recover` method recovers the state of `processor` by replaying event messages that `processor` received in previous application runs. 

### Step 4: Event-sourced actor usage

The event-sourced `processor` can be used like any other actor. Messages of type [`Message`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Message) are appended to `journal` before the `processor`'s `receive` method is called. Messages of any other type are directly received by `processor` without being journaled.

![Event-sourced actor](https://raw.github.com/eligosource/eventsourced/wip-es-trait/doc/images/firststeps-1.png)

    // send event message to processor (will be journaled)
    processor ! Message("foo")

    // send non-event message to processor (will not be journaled)
    processor ! "bar"

A first application application run will create an empty journal. Hence, no event messages will be replayed and the `processor` writes

    received message #1

to `stdout`. When the application is restarted, however, the `processor`'s state will be recovered by replaying the previously journaled event message. Then, the application sends another event message. We will therefore see

    received message #1
    received message #2

on `stdout` where the first `println` is triggered by a replayed event message. The following sections will introduce further library features step by step.

Processors
----------

In this section, additional library features supporting the implementation and usage of event-sourced actors will be presented. Code from this section is contained in [Processors.scala](https://github.com/eligosource/eventsourced/blob/wip-es-trait/src/test/scala/org/eligosource/eventsourced/guide/Processors.scala) and can be executed with `sbt 'test:run-nobootcp org.eligosource.eventsourced.guide.Processors'`

### `Receiver` modification

![Event-sourced receiver actor](https://raw.github.com/eligosource/eventsourced/wip-es-trait/doc/images/processors-1.png)

Processors can additionally be modified with the stackable [`Receiver`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Receiver) trait to pattern match against events directly, instead of event `Message`s. For example,

    val processor: ActorRef = extension.processorOf(ProcessorProps(1, new Processor with Receiver with Eventsourced))

    class Processor extends Actor {
      def receive = {
        case "foo" => println("received event foo")
      }
    }

    processor ! Message("foo")

will write `received event foo` to `stdout`. In order to obtain the current event message, the `Processor` actor must be defined with a `Receiver` self type and use the `Receiver.message` method. This is done in the following example which obtains the sequence number of the current event message.

    class Processor extends Actor { this: Receiver =>
      def receive = {
        case "foo" => println("received event foo (sequence number = %d)" format message.sequenceNr)
      }
    }

    processor ! Message("foo")

This will write `received event foo (sequence number = 2)` to `stdout` (the actual sequence number may differ). Refer to the [`Receiver`](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.Receiver) API docs for details.

### Processor replies

… 

### Behavior changes

… 

Channels
--------

… 

Appendix A: Legend
------------------

![Legend](https://raw.github.com/eligosource/eventsourced/wip-es-trait/doc/images/legend.png)
