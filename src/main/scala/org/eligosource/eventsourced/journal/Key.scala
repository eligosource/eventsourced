package org.eligosource.eventsourced.journal

private [journal] case class Key(
  componentId: Int,
  initiatingChannelId: Int,
  sequenceNr: Long,
  confirmingChannelId: Int)
