[![Build Status](https://secure.travis-ci.org/eligosource/eventsourced.png)](http://travis-ci.org/eligosource/eventsourced)

This user documentation is work in progress …

- New API documentation is [here](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.package)
- Old user documentation is [here](https://github.com/eligosource/eventsourced/blob/master/README.md).

Eventsourced
============

<i>Eventsourced</i> is a library that adds [event-sourcing](http://martinfowler.com/eaaDev/EventSourcing.html) to [Akka](http://akka.io/) actors. It appends event messages to a journal before they are processed by an actor and recovers actor state by replaying them. Appending event messages to a journal, instead of persisting actor state directly, allows for actor state persistence at very high transaction rates. Persisting changes instead of current state also serves as a foundation to automatically adjust actor state to cope with retroactive changes.

Events produced by an event-sourced actor are sent to destinations via one or more channels. Channels connect an actor to other application parts such as external web services, internal domain services, messaging systems, event archives or other (local or remote) event-sourced actors, to mention only a few examples. During recovery, channels ensure that events produced by an event-sourced actor are not redundantly delivered to destinations. They may also guarantee delivery of produced events by optionally appending them to a journal and removing them once they have been successfully delivered. 

Applications may connect event-sourced actors via channels to arbitrary complex event-sourced actor networks that can be consistently recovered by the library (e.g. after a crash or during normal application start). Channels play an important role during recovery as they ensure that replayed event messages do not wrongly interleave with new event messages created and sent by event-sourced actors. 

Based on these mechanisms, for example, the implementation of reliable, long-running business processes using event-sourced state machines becomes almost trivial. Here, applications may use Akka's [FSM](http://doc.akka.io/docs/akka/2.0.3/scala/fsm.html) (or just plain actors) to implement state machines where persistence and recovery is provided by the <i>Eventsourced</i> library.

… 

API
---

[API documentation](http://eligosource.github.com/eventsourced/#org.eligosource.eventsourced.core.package)

Installation
------------

See [Installation](https://github.com/eligosource/eventsourced/wiki/Installation) Wiki page.

Forum
-----

[Eventsourced](http://groups.google.com/group/eventsourced) Google Group