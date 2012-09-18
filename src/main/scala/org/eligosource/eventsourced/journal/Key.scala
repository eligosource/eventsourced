package org.eligosource.eventsourced.journal

private [journal] case class Key(
  processorId: Int,
  initiatingChannelId: Int,
  sequenceNr: Long,
  confirmingChannelId: Int)
