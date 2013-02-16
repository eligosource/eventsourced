package org.eligosource.eventsourced.journal.dynamodb

import org.eligosource.eventsourced.journal.common.JournalSpec
import org.eligosource.eventsourced.core.JournalProps
import akka.actor.ActorSystem

class DynamoDBJournalSpec extends JournalSpec {


  def journalProps: JournalProps = {
    val key = sys.env("AWS_ACCESS_KEY_ID")
    val secret = sys.env("AWS_SECRET_ACCESS_KEY")
    val table = sys.env("TEST_TABLE")
    val app = System.currentTimeMillis().toString
    DynamoDBJournalProps(table, app, key, secret, asyncWriterCount = 16, system = ActorSystem("test"))
  }
}

