package org.eligosource.eventsourced.journal.dynamodb

trait DynamoDBJournalSupport {
  val journalProps = {
    val key = sys.env("AWS_ACCESS_KEY_ID")
    val secret = sys.env("AWS_SECRET_ACCESS_KEY")
    val table = sys.env("TEST_TABLE")
    val app = System.currentTimeMillis().toString
    val concurrency = sys.env.get("CONCURRENCY").map(_.toInt).getOrElse(4)
    DynamoDBJournalProps(table, app, key, secret, asyncWriterCount = concurrency, system = system)
  }
}
