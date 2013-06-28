# MongoDB Casbah Journal

[Eventsourced](https://github.com/eligosource/eventsourced) applications can create a [mongoDB](http://www.mongodb.org/) Casbah backed journal.

- Using the [Casbah](http://api.mongodb.org/scala/casbah/2.0/) based [MongodbCasbahJournalProps](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.mongodb.casbah.MongodbCasbahJournalProps) configuration object.

## Properties

A mongoDB backed journal has the following properties when running on a real mongoDB cluster:

- Highly available.
- Horizontal scalability of writes via sharding.
- Horizontal scalability of reads (replay) via sharding.
- Writes evenly distributed via sharding.
- Efficient per-processor recovery.
- Efficient per-channel recovery (applies to reliable channels).

## Status

Experimental. The Casbah based MongoDB journal is fully functional.

Casbah Driver Version: 2.6.2

## Example

This section shows how to initialize a journal that connects to a local, standalone mongoDB instance.

First, download, install and start a standalone mongoDB instance by following the instructions in the mongoDB [Installing MongoDB](http://docs.mongodb.org/manual/installation/). Then add the required dependencies to your project's `build.sbt` file:

    resolvers += "Eligosource Snapshots" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

    libraryDependencies += "org.eligosource" %% "eventsourced-core" % "0.6-SNAPSHOT"

    libraryDependencies += "org.eligosource" %% "eventsourced-journal-mongodb-casbah" % "0.6-SNAPSHOT"

### Mongodb Casbah Based Journal Initialization

    import akka.actor._
    import com.mongodb.casbah.Imports._
    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal.mongodb.casbah.MongodbCasbahJournalProps

    implicit val system = ActorSystem("example")

    // create and start the Casbah based mongoDB journal
    val journal: ActorRef = MongodbCasbahJournalProps(MongoClient(), "eventsourced", "event").createJournal

    // create an event-sourcing extension that uses the Casbah based mongoDB journal
    val extension = EventsourcingExtension(system, journal)

    // ...
