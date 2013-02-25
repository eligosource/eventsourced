package org.eligosource.eventsourced.journal.dynamodb

import DynamoDBJournal._
import akka.actor._
import akka.util.Timeout
import collection.JavaConverters._
import collection.immutable.TreeMap
import com.amazonaws.services.dynamodb.model._
import com.amazonaws.services.dynamodb.model.{Key => DynamoKey}
import com.sclasen.spray.dynamodb.{DynamoDBClientProps, DynamoDBClient}
import concurrent.duration._
import concurrent.{Future, Await}
import java.nio.ByteBuffer
import java.util.Collections
import java.util.{List => JList, Map => JMap, HashMap => JHMap}
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.core.Message
import org.eligosource.eventsourced.core.Serialization
import org.eligosource.eventsourced.journal.common._
import org.eligosource.eventsourced.journal.dynamodb.ConcurrentWriteJournal.SetDeliveryListener
import util.{Failure, Success}


class DynamoDBJournal(props: DynamoDBJournalProps) extends ConcurrentWriteJournal with ActorLogging {


  val serialization = Serialization(context.system)

  implicit def msgToBytes(msg: Message): Array[Byte] = serialization.serializeMessage(msg)

  implicit def msgFromBytes(bytes: Array[Byte]): Message = serialization.deserializeMessage(bytes)

  val channelMarker = Array(1.toByte)
  val countMarker = Array(1.toByte)

  log.debug("new Journal")

  val committedCounterAtt = S(props.eventSourcedApp + CommitCounter)

  val committedCounterKey =
    new DynamoKey()
      .withHashKeyElement(committedCounterAtt)

  def counterAtt(cntr: Long) = S(props.eventSourcedApp + Counter + cntr)

  def counterKey(cntr: Long) =
    new DynamoKey()
      .withHashKeyElement(counterAtt(cntr))

  implicit val ctx = context.system.dispatcher

  val dynamo = new DynamoDBClient(props.clientProps)

  def asyncWriteTimeout: FiniteDuration = props.operationTimeout.duration

  def asyncWriterCount: Int = props.asyncWriterCount

  def writer(id: Int) = new DynamoWriter

  def replayer = new DynamoReplayer

  def journalProps = props

  private val countCommitter = context.actorOf(Props(new DynamoCountCommitter))

  resequencer ! SetDeliveryListener(Some(countCommitter))

  def storedCounter: Long = {
    val max = Long.MaxValue
    val counter = Await.result(findCommittedCounter.flatMap(min => findStoredCounter(min, max)), props.operationTimeout.duration)
    log.debug(s"found stored counter $counter")
    counter
  }

  private def findCommittedCounter: Future[Long] = {
    dynamo.sendGetItem(new GetItemRequest().withKey(committedCounterKey).withTableName(props.journalTable).withConsistentRead(true)).map {
      gir =>
        Option(gir.getItem.get(Data)).map(_.getB.array()).map(counterFromBytes).getOrElse(0L)
    }.recover {
      case e: Exception =>
        log.error("cant find committed counter starting at 0")
        0L
    }
  }

  private def findStoredCounter(min: Long, max: Long): Future[Long] = {
    val candidates = candidateKeys(min, max)
    val ka = new KeysAndAttributes().withKeys(candidates.values.toSeq: _*).withConsistentRead(true)
    val tables = Collections.singletonMap(props.journalTable, ka)
    val get = new BatchGetItemRequest().withRequestItems(tables)
    dynamo.sendBatchGetItem(get).flatMap(getUnprocessedItems) flatMap {
      res =>
        val batch = mapBatch(res.getResponses.get(props.journalTable))
        val counters: List[Long] = candidates.map {
          ///find the counters associated with any found keys
          case (cnt, key) => Option(batch.get(key.getHashKeyElement)).map(_ => cnt)
        }.flatten.toList
        if (counters.size == 0) Future(0) //no counters found
        else if (counters.size == 1 && counters(0) == 1) Future(1) //one counter found
        else if (endsSequentially(counters)) Future(counters.last) // last 2 counters found are sequential so last one is highest
        else findStoredCounter(min, counters.last)
    }
  }

  def endsSequentially(counters: List[Long]): Boolean = {
    val two = counters.takeRight(2)
    two match {
      case a :: b :: Nil if a + 1 == b => true
      case _ => false
    }
  }


  def candidateKeys(min: Long, max: Long): TreeMap[Long, DynamoKey] = {
    val increment: Long = max / 100
    (Stream.iterate(min, 100)(i => i + increment)).map {
      i =>
        i -> counterKey(i)
    }.foldLeft(TreeMap.empty[Long, DynamoKey]) {
      case (tm, (cnt, key)) => tm + (cnt -> key)
    }
  }

  private def replayOut(r: ReplayOutMsgs, replayTo: Long, p: (Message) => Unit): Iterator[Future[Unit]] = {
    val from = r.fromSequenceNr
    val msgs = (replayTo - r.fromSequenceNr).toInt + 1
    log.debug(s"replayingOut from ${from} for up to ${msgs}")
    val replayer = context.actorOf(Props(new ReplayResequencer(from, msgs, p)))
    Stream.iterate(r.fromSequenceNr, msgs)(_ + 1)
      .map(l => (l -> new DynamoKey().withHashKeyElement(outKey(r.channelId, l)))).grouped(100).map {
      keys =>
        log.debug("replayingOut")
        val ka = new KeysAndAttributes().withKeys(keys.map(_._2).asJava).withConsistentRead(true)
        val get = new BatchGetItemRequest().withRequestItems(Collections.singletonMap(props.journalTable, ka))
        dynamo.sendBatchGetItem(get).flatMap(getUnprocessedItems).map {
          resp =>
            val batchMap = mapBatch(resp.getResponses.get(props.journalTable))
            keys.foreach {
              key =>
                val msgOpt = Option(batchMap.get(key._2.getHashKeyElement)).map(item => msgFromBytes(item.get(Data).getB.array()))
                replayer ! (key._1 -> msgOpt)
            }
        }
    }
  }

  private def replayIn(r: ReplayInMsgs, replayTo: Long, processorId: Int, p: (Message) => Unit): Iterator[Future[Unit]] = {
    val from = r.fromSequenceNr
    val msgs = (replayTo - r.fromSequenceNr).toInt + 1
    log.debug(s"replayingIn from ${from} for up to ${msgs}")
    val replayer = context.actorOf(Props(new ReplayResequencer(from, msgs, p)))
    Stream.iterate(r.fromSequenceNr, msgs)(_ + 1)
      .map(l => (l -> new DynamoKey().withHashKeyElement(inKey(r.processorId, l)))).grouped(100).map {
      keys =>
        log.debug("replayingIn")
        val ka = new KeysAndAttributes().withKeys(keys.map(_._2).asJava).withConsistentRead(true)
        val get = new BatchGetItemRequest().withRequestItems(Collections.singletonMap(props.journalTable, ka))
        dynamo.sendBatchGetItem(get).flatMap(getUnprocessedItems).flatMap {
          resp =>
            val batchMap = mapBatch(resp.getResponses.get(props.journalTable))
            val messages = keys.map {
              key =>
                val msgOpt = Option(batchMap.get(key._2.getHashKeyElement)).map(item => msgFromBytes(item.get(Data).getB.array()))
                key._1 -> msgOpt
            }

            confirmingChannels(processorId, messages, replayer)
        }
    }
  }

  def confirmingChannels(processorId: Int, messages: Stream[(Long, Option[Message])], replayer: ActorRef): Future[Unit] = {

    val counterOptKeys: Stream[(Long, Option[DynamoKey])] = messages.map {
      case (counter, messageOpt) =>
        counter -> messageOpt.map(message => new DynamoKey()
          .withHashKeyElement(ackKey(processorId, message.sequenceNr)))
    }

    val ks = counterOptKeys.map(_._2).flatten
    if (ks.nonEmpty) {
      val ka = new KeysAndAttributes().withKeys(ks.asJava).withConsistentRead(true)
      val get = new BatchGetItemRequest().withRequestItems(Collections.singletonMap(props.journalTable, ka))
      dynamo.sendBatchGetItem(get).flatMap(getUnprocessedItems).map {
        response =>
          val batchMap = mapBatch(response.getResponses.get(props.journalTable))
          val acks: Stream[Option[Seq[Int]]] = counterOptKeys.map {
            counterOptKey =>
              counterOptKey._2.flatMap {
                key =>
                  Option(batchMap.get(key.getHashKeyElement)).map {
                    _.keySet().asScala.filter(!DynamoKeys.contains(_)).map(_.toInt).toSeq
                  }
              }
          }

          messages.zip(acks).foreach {
            case ((counter, Some(message)), Some(chAcks)) => replayer ! (counter -> Some(message.copy(acks = chAcks)))
            case ((counter, Some(message)), None) => replayer ! (counter -> Some(message))
            case ((counter, None), None) => replayer ! (counter -> None)
            case ((counter, None), Some(_)) => context.system.log.error("ACKS Without MSG Found!")
          }
      }
    } else {
      Future.successful(messages.foreach(co => replayer ! co))
    }
  }

  def getUnprocessedItems(result: BatchGetItemResult): Future[BatchGetItemResult] = {
    if (result.getUnprocessedKeys.size() == 0) Future.successful(result)
    else {
      log.warning("UNPROCESSED ITEMS IN BATCH GET")
      Future.sequence {
        result.getUnprocessedKeys.get(props.journalTable).getKeys.asScala.map {
          k =>
            val g = new GetItemRequest().withTableName(props.journalTable).withKey(k).withConsistentRead(true)
            getUnprocessedItem(g)
        }
      }.map {
        results =>
          val items = result.getResponses.get(props.journalTable).getItems
          results.foreach {
            i => items.add(i.getItem)
          }
          result
      }
    }
  }

  def getUnprocessedItem(g: GetItemRequest, retries: Int = 5): Future[GetItemResult] = {
    if (retries == 0) throw new RuntimeException(s"couldnt get ${g.getKey} after 5 tries")
    dynamo.sendGetItem(g).fallbackTo(getUnprocessedItem(g, retries - 1))
  }


  def mapBatch(b: BatchResponse) = {
    val map = new JHMap[AttributeValue, JMap[String, AttributeValue]]
    b.getItems.iterator().asScala.foreach {
      item => map.put(item.get(Id), item)
    }
    map
  }


  def put(key: DynamoKey, message: Array[Byte]): PutRequest = {
    val item = new JHMap[String, AttributeValue]
    log.debug(s"put:  ${key.toString}")
    item.put(Id, key.getHashKeyElement)
    item.put(Data, B(message))
    new PutRequest().withItem(item)
  }

  def putAck(ack: WriteAck): PutRequest = {
    val item = new JHMap[String, AttributeValue]
    item.put(Id, ack.getHashKeyElement)
    item.put(ack.channelId.toString, B(channelMarker))
    new PutRequest().withItem(item)
  }


  def S(value: String): AttributeValue = new AttributeValue().withS(value)

  def N(value: Long): AttributeValue = new AttributeValue().withN(value.toString)

  def NS(value: Long): AttributeValue = new AttributeValue().withNS(value.toString)

  def B(value: Array[Byte]): AttributeValue = new AttributeValue().withB(ByteBuffer.wrap(value))

  def UB(value: Array[Byte]): AttributeValueUpdate = new AttributeValueUpdate().withAction(AttributeAction.PUT).withValue(B(value))

  def inKey(procesorId: Int, sequence: Long) = S(str(props.eventSourcedApp, "IN-", procesorId, "-", sequence)) //dont remove those dashes or else keys will be funky

  def outKey(channelId: Int, sequence: Long) = S(str(props.eventSourcedApp, "OUT-", channelId, "-", sequence))

  def ackKey(processorId: Int, sequence: Long) = S(str(props.eventSourcedApp, "ACK-", processorId, "-", sequence))

  def str(ss: Any*): String = ss.foldLeft(new StringBuilder)(_.append(_)).toString()

  implicit def inToDynamoKey(cmd: WriteInMsg): DynamoKey =
    new DynamoKey()
      .withHashKeyElement(inKey(cmd.processorId, cmd.message.sequenceNr))

  implicit def outToDynamoKey(cmd: WriteOutMsg): DynamoKey =
    new DynamoKey()
      .withHashKeyElement(outKey(cmd.channelId, cmd.message.sequenceNr))

  implicit def delToDynamoKey(cmd: DeleteOutMsg): DynamoKey =
    new DynamoKey().
      withHashKeyElement(outKey(cmd.channelId, cmd.msgSequenceNr))

  implicit def ackToDynamoKey(cmd: WriteAck): DynamoKey =
    new DynamoKey()
      .withHashKeyElement(ackKey(cmd.processorId, cmd.ackSequenceNr))

  implicit def replayInToDynamoKey(cmd: ReplayInMsgs): DynamoKey =
    new DynamoKey().withHashKeyElement(inKey(cmd.processorId, cmd.fromSequenceNr))

  implicit def replayOutToDynamoKey(cmd: ReplayOutMsgs): DynamoKey =
    new DynamoKey().withHashKeyElement(outKey(cmd.channelId, cmd.fromSequenceNr))


  class DynamoWriter extends Writer {

    val dynamoWriter = new DynamoDBClient(props.clientProps)

    def executeDeleteOutMsg(cmd: DeleteOutMsg) = {
      val del: DeleteItemRequest = new DeleteItemRequest().withTableName(props.journalTable).withKey(cmd)
      dynamoWriter.sendDeleteItem(del)
    }

    def executeWriteOutMsg(cmd: WriteOutMsg) = {
      val msg = put(cmd, cmd.message.clearConfirmationSettings)
      val cnt = put(counterKey(cmd.message.sequenceNr), countMarker)
      if (cmd.ackSequenceNr != SkipAck) batchWrite(msg, cnt, putAck(WriteAck(cmd.ackProcessorId, cmd.channelId, cmd.ackSequenceNr)))
      else batchWrite(msg, cnt)
    }

    def executeWriteInMsg(cmd: WriteInMsg) = {
      log.debug(s"batch in with counter ${cmd.message.sequenceNr}")
      batchWrite(put(cmd, cmd.message.clearConfirmationSettings),
        put(counterKey(cmd.message.sequenceNr), countMarker)
      )
    }

    def executeWriteAck(cmd: WriteAck) = {
      batchWrite(putAck(cmd))
    }

    def batchWrite(puts: PutRequest*) = {
      log.debug("batchWrite {}", puts.head.toString)
      val write = new JHMap[String, JList[WriteRequest]]
      val writes = puts.map(new WriteRequest().withPutRequest(_)).asJava
      write.put(props.journalTable, writes)
      val batch = new BatchWriteItemRequest().withRequestItems(write)
      dynamoWriter.sendBatchWriteItem(batch).flatMap(sendUnprocessedItems)
    }

    def sendUnprocessedItems(result: BatchWriteItemResult): Future[BatchWriteItemResult] = {
      if (result.getUnprocessedItems.size() == 0) Future.successful(result)
      else {
        log.warning("UNPROCESSED ITEMS IN BATCH PUT")
        Future.sequence {
          result.getUnprocessedItems.get(props.journalTable).asScala.map {
            w =>
              val p = new PutItemRequest().withTableName(props.journalTable).withItem(w.getPutRequest.getItem)
              sendUnprocessedItem(p)
          }
        }.map {
          results =>
            result.getUnprocessedItems.clear()
            result //just return the original result
        }
      }
    }


    def sendUnprocessedItem(p: PutItemRequest, retries: Int = 5): Future[PutItemResult] = {
      if (retries == 0) throw new RuntimeException(s"couldnt put ${p.getItem.get(Id)} after 5 tries")
      dynamo.sendPutItem(p).fallbackTo(sendUnprocessedItem(p, retries - 1))
    }
  }


  class DynamoReplayer extends Replayer {
    def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit, sender: ActorRef, replayTo: Long) {
      val replayOps = cmds.map {
        cmd =>
          Future.sequence(replayIn(cmd, replayTo, cmd.processorId, p(_, cmd.target)))
      }
      Future.sequence(replayOps).onComplete {
        case Success(_) => sender ! ReplayDone
        case Failure(x) => log.error(x, "Failure:executeBatchReplayInMsgs")
      }
    }

    def executeReplayInMsgs(cmd: ReplayInMsgs, p: (Message) => Unit, sender: ActorRef, replayTo: Long) {
      val replayOps = Future.sequence(replayIn(cmd, replayTo, cmd.processorId, p))
      replayOps.onComplete {
        case Success(_) => sender ! ReplayDone
        case Failure(x) => log.error(x, "Failure:executeReplayInMsgs")
      }
    }

    def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: (Message) => Unit, sender: ActorRef, replayTo: Long) {
      Future.sequence(replayOut(cmd, replayTo, p)).onComplete {
        case Success(_) => log.debug("executeReplayOutMsgs")
        case Failure(x) => log.error(x, "Failure:executeReplayOutMsgs")
      }
    }
  }

  class ReplayResequencer(start: Long, maxMessages: Long, p: (Message) => Unit) extends Actor {

    import scala.collection.mutable.Map

    private val delayed = Map.empty[Long, Option[Message]]
    private var delivered = start - 1
    private val done = start + maxMessages - 1

    def receive = {
      case (seqnr: Long, Some(message: Message)) => resequence(seqnr, Some(message))
      case (seqnr: Long, None) => resequence(seqnr, None)
    }

    @scala.annotation.tailrec
    private def resequence(seqnr: Long, m: Option[Message]) {
      if (seqnr == delivered + 1) {
        delivered = seqnr
        m.foreach(p)
      } else {
        delayed += (seqnr -> (m))
      }
      val eo = delayed.remove(delivered + 1)
      if (eo.isDefined) resequence(delivered + 1, eo.get)
      else if (delivered == done) {
        log.debug("replay resequencer finished, shutting down")
        self ! PoisonPill
      }
    }

  }

  class DynamoCountCommitter extends Actor {

    val writer = new DynamoWriter

    def receive = {
      case count: Long if count % 100 == 0 => writeCommittedCounter(committedCounterKey, count)
      case count: Long => ()
    }

    private def writeCommittedCounter(key: DynamoKey, count: Long): Future[BatchWriteItemResult] = {
      writer.batchWrite(put(key, count))
    }

  }


}

object DynamoDBJournal {

  val Id = "key"
  val Data = "data"
  val Counter = "COUNTER"
  val CommitCounter = "COMMITCOUNTER"
  val DynamoKeys = Set(Id)

  import concurrent.ExecutionContext.Implicits.global

  val hashKey = new KeySchemaElement().withAttributeName("key").withAttributeType("S")
  val schema = new KeySchema().withHashKeyElement(hashKey)
  lazy val utilDynamoSystem = ActorSystem("dynamo-util")
  implicit lazy val utilDynamo = new DynamoDBClient(new DynamoDBClientProps(sys.env("AWS_ACCESS_KEY_ID"), sys.env("AWS_SECRET_ACCESS_KEY"), Timeout(10 seconds), dynamoSystem, dynamoSystem))

  def createJournal(table: String, read:Long, write:Long) {
    utilDynamo.sendListTables(new ListTablesRequest()).map {
      case r if r.getTableNames.contains(table) => waitForActiveTable(table)
      case _ =>
        utilDynamo.sendCreateTable(new CreateTableRequest(table, schema).withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(read).withWriteCapacityUnits(write)))
        waitForActiveTable(table)
    }.map {
      _ => println(s"$table created.")
    }
  }

  def waitForActiveTable(table: String, retries: Int = 100) {
    if (retries == 0) throw new RuntimeException("Timed out waiting for creation of:" + table)
    val desc = Await.result(utilDynamo.sendDescribeTable(new DescribeTableRequest().withTableName(table)), 10 seconds)
    if (desc.getTable.getTableStatus != "ACTIVE") {
      Thread.sleep(1000)
      println(s"waiting for $table to be ACTIVE")
      waitForActiveTable(table, retries - 1)
    }
  }

  def scaleThroughput(table: String, read: Int, write: Int):Future[Unit]= {
    utilDynamo.sendDescribeTable(new DescribeTableRequest().withTableName(table)).map {
      dtr =>
        val tp = dtr.getTable.getProvisionedThroughput
        val cr = tp.getReadCapacityUnits
        val cw = tp.getWriteCapacityUnits
        val (readInc, writeInc) = getNextIncrement(cr, read, cw, write)
        println(s"$table throughput is read:${tp.getReadCapacityUnits}, write:${tp.getWriteCapacityUnits}, last-increased:${tp.getLastIncreaseDateTime}, last-dcreased:${tp.getLastDecreaseDateTime}")
        if (readInc == cr && writeInc == cw) {} else {
          println(s"$table altering read from $cr to $readInc, write $cw to $writeInc")
          utilDynamo.sendUpdateTable(new UpdateTableRequest().withTableName(table).withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(readInc).withWriteCapacityUnits(writeInc))).onFailure {
            case e: Exception => println(s"$table failed to provision throughput, read:${readInc} write:${writeInc} ${e.getClass.getSimpleName} ${e.getMessage}")
          }
          waitForActiveTable(table)
          scaleThroughput(table, read, write)
        }
    }


  }

  def getNextIncrement(cr: Long, dr: Long, cw: Long, dw: Long): (Long, Long) = {
    (next(cr, dr), next(cw, dw))
  }

  def next(c: Long, d: Long):Long= {
    if (c * 2 > d) d
    else c * 2
  }

  def stop(){
    utilDynamoSystem.shutdown()
    utilDynamoSystem.awaitTermination()
  }


}



