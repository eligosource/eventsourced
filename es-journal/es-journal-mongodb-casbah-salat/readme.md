# MongoDB Casbah & Salat Journal

[Eventsourced](https://github.com/eligosource/eventsourced) applications can create a [mongoDB](http://www.mongodb.org/) Casbah & Salat backed journal.

- Using the [Casbah](http://api.mongodb.org/scala/casbah/2.0/) based [MongodbCasbahSalatJournalProps](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbCasbahSalatJournalProps) configuration object.

## Properties

A mongoDB backed journal has the following properties when running on a real mongoDB cluster:

- Highly available.
- Horizontal scalability of writes via sharding.
- Horizontal scalability of reads (replay) via sharding.
- Writes evenly distributed via sharding.
- Efficient per-processor recovery.
- Efficient per-channel recovery (applies to reliable channels).
- Support of snapshotting 
- Serialization of messages and snapshots to JSON format
- Out-of-the-box support of MongoDB queries to snapshots (one can extend [SalatDAO](https://github.com/novus/salat/wiki/SalatDAO)) 

## Status

Experimental. The Casbah & Salat based MongoDB journal is fully functional.

## Example

This section shows how to initialize a journal that connects to a local, standalone mongoDB instance.

First, download, install and start a standalone mongoDB instance by following the instructions in the mongoDB [Installing MongoDB](http://docs.mongodb.org/manual/installation/). Then add the required dependencies to your project's `build.sbt` file:

    resolvers += "Eligosource Snapshots" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

    libraryDependencies += "org.eligosource" %% "eventsourced-core" % "0.6-SNAPSHOT"

    libraryDependencies += "org.eligosource" %% "eventsourced-journal-mongodb-casbah-salat" % "0.6-SNAPSHOT"

### @Salat interfaces

Please extend your events directly (current limitation of Salat annotations) from MongodbEvent trait and your snapshots (states) from [MongodbSnapshotState](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbSnapshotState) trait. 
One can process List[MongodbSnapshotState] on receiving SnapshotRequest and by this trick split in-memory model into blocks < 16MB.  

### MongoDB queries with Salat

The document being stored with your [MongodbSnapshotState](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbSnapshotState) in mongoDB has the structure of [MongodbSnapshot](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbSnapshot):

{
processorId: 1, 
timestamp: 1L, 
snapshotNr: 1, 
state: MongodbSnapshotState
}

OR

{
processorId: 1, 
timestamp: 1L, 
snapshotNr: 1, 
state: List[MongodbSnapshotState]
}

depending on how you process the SnapshotRequest and how do you split your in-memory model!  

Please refer to [SalatDAO](https://github.com/novus/salat/wiki/SalatDAO) wiki page for the support on making queries to your snapshots.

### Mongodb Casbah & Salat Based Journal Initialization

    import akka.actor._
    import com.mongodb.casbah.Imports._
    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbCasbahSalatJournalProps

    implicit val system = ActorSystem("example")

    // create and start the Casbah based mongoDB journal
    val journal: ActorRef = Journal(MongodbCasbahSalatJournalProps(MongoClient(), "eventsourced", "events", "snapshots"))

    // create an event-sourcing extension that uses the Casbah based mongoDB journal
    val extension = EventsourcingExtension(system, journal)

    // ...
