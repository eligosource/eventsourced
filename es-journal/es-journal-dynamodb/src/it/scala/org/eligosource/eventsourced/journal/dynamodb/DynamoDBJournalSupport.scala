package org.eligosource.eventsourced.journal.dynamodb

import akka.actor.{DeadLetter, Props, Actor, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, Suite, BeforeAndAfterEach}
import org.eligosource.eventsourced.journal.common.JournalProps
import akka.util.Timeout
import concurrent.duration._

trait DynamoDBJournalSupport extends BeforeAndAfterEach with BeforeAndAfterAll {
  this: Suite =>

  var _app = System.currentTimeMillis().toString
  val spraySystem = ActorSystem("spray")

  def journalProps: JournalProps = {
    val key = sys.env("AWS_ACCESS_KEY_ID")
    val secret = sys.env("AWS_SECRET_ACCESS_KEY")
    val table = sys.env("TEST_TABLE")
    val app = _app
    DynamoDBJournalProps(table, app, key, secret, counterShards = 10, system = spraySystem, operationTimeout = Timeout(30 seconds))
  }

  override protected def afterEach() {
    _app = System.currentTimeMillis().toString
  }

  override protected def afterAll() {
    spraySystem.shutdown()
    spraySystem.awaitTermination()
  }
}

class Listener extends Actor {
  def receive = {
    case a:DeadLetter ⇒ println(a)
  }
}
