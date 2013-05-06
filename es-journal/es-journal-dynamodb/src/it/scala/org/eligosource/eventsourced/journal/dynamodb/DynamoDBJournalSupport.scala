package org.eligosource.eventsourced.journal.dynamodb

import akka.actor.ActorSystem

trait DynamoDBJournalSupport {
  def journalProps = {
    val key = sys.env("AWS_ACCESS_KEY_ID")
    val secret = sys.env("AWS_SECRET_ACCESS_KEY")
    val table = sys.env("TEST_TABLE")
    val app = System.currentTimeMillis().toString
    DynamoDBJournalProps(table, app, key, secret, counterShards = 10, system = ActorSystem("dynamo-test"))
  }
}
