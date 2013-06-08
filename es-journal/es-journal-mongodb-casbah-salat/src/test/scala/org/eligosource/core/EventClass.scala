package org.eligosource.eventsourced.core

import org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbEvent

case class EventClass(s: String) extends MongodbEvent
