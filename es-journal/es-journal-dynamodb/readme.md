DynamoDB Journal
================

This is the DynamoDB backed Eventsourced Journal implementation.

To use this journal, you will need to create a DynamoDB table in your AWS account. This can be done manually, or with the
table creation utility included in this implementation.

## Throughput

*Note* please take care to provision ample write throughput for your application.

A good rule of thumb is to provision one write unit per eventsourced message per second in your app when using processors and default channels,
and two write units per eventsourced message per second in your app when using processors and reliable channels.

### EC2 Instance sizing

Note that for best throughput you should run the journal on an EC2 instance in the same region as your table.

If you plan on writing more than 1000 messages per second or so, you should use a Cluster Compute instance that has 10 Gig networking,
as this journal implementation is network bound.

## Configure DynamoDB

Here is an example of using the table creation utility to create a DynamoDB table for your application's journal.
It assumes that your AWS key and secret are set as the environment variables AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY,
and that eventsourced and the dynamodb journal implementation are dependencies in your project.

```
$ sbt console
[info] Starting scala interpreter...
[info]
Welcome to Scala version 2.10.0 (Java HotSpot(TM) 64-Bit Server VM, Java 1.7.0_09).
Type in expressions to have them evaluated.
Type :help for more information.

scala> import org.eligosource.eventsourced.journal.dynamodb.DynamoDBJournal
import org.eligosource.eventsourced.journal.dynamodb.DynamoDBJournal

scala> val readThroughput = 100
readThroughput: Int = 100

scala> val writeThroughput = 100
writeThroughput: Int = 100

scala> val tableName = "my-journal-table"
tableName: String = my-journal-table

scala> DynamoDBJournal.createJournal(tableName, readThroughput, writeThroughput)
[INFO] [03/11/2013 11:48:56.566] [dynamo-util-spray.io.io-bridge-dispatcher-6] [akka://dynamo-util/user/io-bridge] akka://dynamo-util/user/io-bridge started
[INFO] [03/11/2013 11:48:56.626] [dynamo-util-akka.actor.default-dispatcher-4] [akka://dynamo-util/user/default-http-client] Starting akka://dynamo-util/user/default-http-client

scala> waiting for my-journal-table to be ACTIVE
waiting for my-journal-table to be ACTIVE
waiting for my-journal-table to be ACTIVE
waiting for my-journal-table to be ACTIVE
...
waiting for my-journal-table to be ACTIVE
waiting for my-journal-table to be ACTIVE
my-journal-table created.

scala> DynamoDBJournal.utilDynamoSystem.shutdown

[success] Total time: 72 s, completed Mar 11, 2013 11:53:50 AM

>

```

The utilities also include a method to scale the throughput of your table up or down.  Since you can only increase throughput by a factor of 2 per api call,
the utility takes care of waiting for the table to be active and increasing the throughput again until the target throughput is reached.

This makes scaling up to do throughput testing straightforward.

*NOTE* AWS WILL ONLY ALLOW YOU TO SCALE DOWN YOUR THROUGHPUT ONCE PER 24HRS. Please be careful when using lots of throughput for testing.


## Using the DynamoDB Journal

Once you have DynamoDB configured properly, you can start using the journal. In addition to the table name, you will need to select
an application name to use with the journal, since one DynamoDB table can support multiple journals concurrently.

Here is a basic example of how you can set up an eventsourced actor system, using the DynamoDB journal.

```
import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal.dynamodb.DynamoDBJournalProps
import akka.actor._
import concurrent.duration._

implicit val actorSystem = ActorSystem("example")
implicit val timeout = Timeout(5 seconds)

import system.dispatcher

val key    = sys.env("AWS_ACCESS_KEY_ID")
val secret = sys.env("AWS_SECRET_ACCESS_KEY")
val table  = "my-journal-table"
val app    = "my-eventsourced-app"
val props  = DynamoDBJournalProps(table, app, key, secret, asyncWriterCount = 16, system = actorSystem)

// Event sourcing extension
val extension = EventsourcingExtension(system, props.createJournal)

// create processors and channels
val p = extension.processorOf(...)
val c = extension.channelOf(...)

//recover application state
extension.recover()

//have at it

p ! Message("go-go")

```