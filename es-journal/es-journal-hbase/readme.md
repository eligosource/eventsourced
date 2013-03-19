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

Getting started 
---------------

This section shows how to initialize an HBase journal that connects to a local, standalone HBase instance.

First, download, install and start a standalone HBase instance by following the instructions in the HBase [quick start guide](http://hbase.apache.org/book/quickstart.html). Then, under sbt (started from the `eventsourced` project root) run:

    > project eventsourced-journal-hbase
    > run-main org.eligosource.eventsourced.journal.hbase.CreateSchema

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

Cluster setup
-------------

For storing event messages to a real HBase cluster, a table must be initially created with the [CreateSchema](http://eligosource.github.com/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.hbase.CreateSchema) utility as shown in the following example:

    import org.apache.hadoop.conf.Configuration
    import org.eligosource.eventsourced.journal.hbase.CreateSchema
    
    class Temp {
      val config: Configuration = ... // HBase/Hadoop configuration
      val regions = 16                // number of predefined regions
    
      CreateSchema(config, regions)
    }

This creates an event message table that is pre-split into 16 regions. The journal actor will evenly distribute (partition) event messages across regions. This requires the `partitionCount` of the `HBaseJournalProps` configuration object to match the number of `regions` used for table creation. An initially defined `partitionCount` must not be changed later for an existing event message table.
