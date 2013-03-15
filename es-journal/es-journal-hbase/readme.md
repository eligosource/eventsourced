HBase Journal
=============

[Eventsourced](https://github.com/eligosource/eventsourced) applications can create an [HBase](http://hbase.apache.org) backed journal using the [HBaseJournalProps](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.hbase.HBaseJournalProps) configuration object.

Properties
----------

An HBase backed journal has the following properties when running on a real HBase cluster:

- Highly available.
- Horizontal scalability of writes by adding nodes.
- Horizontal scalability of reads (replay) by adding nodes.
- Writes are evenly distributed across regions (region servers)
- All reads and writes are asynchronous and non-blocking.
- Efficient per-processor recovery.
- Efficient per-channel recovery (applies to reliable channels).

Status
------

Experimental. The HBase journal is fully functional, open issues are mainly related to recovery performance.

Example
-------

This section shows how to initialize an HBase journal that connects to a local, standalone HBase instance.

First, download, install and start a standalone HBase instance by following the instructions in the HBase [quick start guide](http://hbase.apache.org/book/quickstart.html). Then, under sbt (started from the `eventsourced` project root) run:

    > project eventsourced-journal-hbase
    > test:run-main org.eligosource.eventsourced.journal.hbase.CreateSchema

Add the required depedencies to your project's `build.sbt` file:

    resolvers += "Eligosource Snapshots" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

    libraryDependencies += "org.eligosource" %% "eventsourced-core" % "0.5-SNAPSHOT"

    libraryDependencies += "org.eligosource" %% "eventsourced-journal-hbase" % "0.5-SNAPSHOT"

Initialize the HBase journal in your application:

    import akka.actor._
    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal.hbase.HBaseJournalProps

    implicit val system = ActorSystem("example")

    // create and start the HBase journal
    val journal: ActorRef = Journal(HBaseJournalProps("localhost"))

    // create an event-sourcing extension that uses the HBase journal
    val extension = EventsourcingExtension(system, journal)

    // ...

