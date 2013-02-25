package org.eligosource.eventsourced.journal.dynamodb

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.util.Timeout
import com.sclasen.spray.dynamodb.DynamoDBClientProps
import concurrent.duration._
import org.eligosource.eventsourced.core.JournalProps


case class DynamoDBJournalProps(journalTable: String, eventSourcedApp: String,
                                key: String, secret: String,
                                operationTimeout: Timeout = Timeout(10 seconds), asyncWriterCount: Int = 16,
                                system: ActorSystem, factory: Option[ActorRefFactory] = None,
                                val name:Option[String]=None,  val dispatcherName:Option[String]=None) extends JournalProps {

  /**
   * Creates a [[org.eligosource.eventsourced.core.Journal]] actor instance.
   */
  def journal = new DynamoDBJournal(this)

  def clientProps = DynamoDBClientProps(key, secret, operationTimeout, system, factory.getOrElse(system))

}