# MongoDB Reactive Journal

[Eventsourced](https://github.com/eligosource/eventsourced) applications can create a [mongoDB](http://www.mongodb.org/) [reactivemongo](http://reactivemongo.org/) backed journal using the [MongodbReactiveJournalProps](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.mongodb.reactive.MongodbReactiveJournalProps) configuration object.

## Properties

A mongoDB reactive journal has the following properties when running on a real mongoDB cluster:

- Highly available.
- Horizontal scalability of writes via sharding.
- Horizontal scalability of reads (replay) via sharding.
- Writes evenly distributed via sharding.
- All reads and writes are asynchornous and non-blocking.
- Efficient per-processor recovery.
- Efficient per-channel recovery (applies to reliable channels).

## Status

Experimental but fully functional.

## Example

This section shows how to initialize a journal that connects to a local, standalone mongoDB instance.

First, download, install and start a standalone mongoDB instance by following the instructions in the mongoDB [Installing MongoDB](http://docs.mongodb.org/manual/installation/). Then add the required dependencies to your project's `build.sbt` file:

    resolvers += "Eligosource Snapshots" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

    libraryDependencies += "org.eligosource" %% "eventsourced-core" % "0.6-SNAPSHOT"

    libraryDependencies += "org.eligosource" %% "eventsourced-journal-mongodb-reactive" % "0.6-SNAPSHOT"

### Mongodb Reactive Based Journal Initialization

    import akka.actor._
    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal.mongodb.reactive.MongodbReactiveJournalProps

    implicit val system = ActorSystem("example")

    // create and start the reactive based mongoDB journal
    val journal: ActorRef = MongodbReactiveJournalProps(List("localhost:27017").createJournal

    // create an event-sourcing extension that uses the ReactiveMongo based mongoDB journal
    val extension = EventsourcingExtension(system, journal)

    // ...
