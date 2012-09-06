package org.eligosource.eventsourced.journal

import java.nio.ByteBuffer

private [journal] case class Key(
  componentId: Int,
  initiatingChannelId: Int,
  sequenceNr: Long,
  confirmingChannelId: Int) {

  def bytes = {
    val bb = ByteBuffer.allocate(20)
    bb.putInt(componentId)
    bb.putInt(initiatingChannelId)
    bb.putLong(sequenceNr)
    bb.putInt(confirmingChannelId)
    bb.array
  }
}

private [journal] case object Key {
  def apply(bytes: Array[Byte]): Key = {
    val bb = ByteBuffer.wrap(bytes)
    val componentId = bb.getInt
    val initiatingChannelId = bb.getInt
    val sequenceNumber = bb.getLong
    val confirmingChannelId = bb.getInt
    new Key(componentId, initiatingChannelId, sequenceNumber, confirmingChannelId)
  }
}

