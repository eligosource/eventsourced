package org.eligosource.eventsourced.journal.dynamodb

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.util.Timeout
import com.sclasen.spray.dynamodb.DynamoDBClientProps
import concurrent.duration._
import org.eligosource.eventsourced.core.JournalProps


case class DynamoDBJournalProps(journalTable: String, eventSourcedApp: String, key: String, secret: String, maxRetries: Int = 3, connectionTimeout: Int = 10000, socketTimeout: Int = 10000, operationTimeout: Timeout = Timeout(10 seconds), asyncWriterCount: Int = 16, system: ActorSystem, factory: Option[ActorRefFactory] = None) extends JournalProps {

  /**
   * Optional channel name.
   */
  def name: Option[String] = None

  /**
   * Optional dispatcher name.
   */
  def dispatcherName: Option[String] = None

  /**
   * Creates a [[org.eligosource.eventsourced.core.Journal]] actor instance.
   */
  def journal = new DynamoDBJournal(this)

  def clientProps = DynamoDBClientProps(key, secret, operationTimeout, system, factory.getOrElse(system))

}