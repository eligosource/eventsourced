HBase Journal
=============

[Eventsourced](https://github.com/eligosource/eventsourced) applications create an [HBase](http://hbase.apache.org) backed journal using the [HBaseJournalProps](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.hbase.HBaseJournalProps) configuration object.

Properties
----------

An HBase backed journal has the following properties when running on a real HBase cluster:

- High availability.
- Horizontal scalability of writes by adding nodes.
- Horizontal scalability of reads (replay) by adding nodes.
- Writes are evenly distributed across regions (region servers)
- All reads and writes are asynchronous and non-blocking.
- Efficient per-processor recovery.
- Efficient per-channel recovery (applies to reliable channels).

Status
------

Experimental but fully functional.

Getting started
---------------

This section shows how to initialize an HBase journal that connects to a local, standalone HBase instance.

First, download, install and start a standalone HBase instance by following the instructions in the HBase [quick start guide](http://hbase.apache.org/book/quickstart.html). Then start `sbt` from the `eventsourced` project root and enter:

    > project eventsourced-journal-hbase
    > org.eligosource.eventsourced.journal.hbase.CreateTable localhost

Add the required depedencies to your project's `build.sbt` file:

    resolvers += "Eligosource Snapshots" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

    libraryDependencies += "org.eligosource" %% "eventsourced-core" % "0.6-SNAPSHOT"

    libraryDependencies += "org.eligosource" %% "eventsourced-journal-hbase" % "0.6-SNAPSHOT"

Initialize the HBase journal in your application:

    import akka.actor._
    import org.eligosource.eventsourced.core._
    import org.eligosource.eventsourced.journal.hbase.HBaseJournalProps

    implicit val system = ActorSystem("example")

    // create and start the HBase journal
    val journal: ActorRef = HBaseJournalProps("localhost").createJournal

    // create an event-sourcing extension that uses the HBase journal
    val extension = EventsourcingExtension(system, journal)

    // ...

Cluster setup
-------------

For storing event messages to a real HBase cluster, a table must be initially created with the [CreateTable](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.hbase.CreateTable$) utility as shown in the following example:

    import org.eligosource.eventsourced.journal.hbase.CreateTable

    class Example {
      val zookeeperQuorum = "localhost:2181" // comma separated list of servers in the ZooKeeper quorum
      val tableName       = "event"          // name of the event message table to be created
      val partitionCount  = 16               // number of regions the event message table is pre-split
  
      CreateTable(zookeeperQuorum, tableName, partitionCount)
    }

This creates an event message table with the name `event` that is pre-split into 16 regions. The journal actor will evenly distribute (partition) event messages across regions.
