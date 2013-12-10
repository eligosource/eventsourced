package org.eligosource.eventsourced.journal.common

import akka.actor.{ActorRef, Actor}
import org.eligosource.eventsourced.core.JournalProtocol.ReadCommand

class ReadOnlyFacade(journal: ActorRef) extends Actor {
  def receive = {
    case cmd: ReadCommand => journal forward cmd
    case msg => //noop
  }
}
