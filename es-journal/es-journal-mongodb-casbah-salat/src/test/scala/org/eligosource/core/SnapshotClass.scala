package org.eligosource.eventsourced.core

import org.eligosource.eventsourced.journal.mongodb.casbah.salat.MongodbSnapshotState

case class SnapshotClass(cnt: Int) extends MongodbSnapshotState
