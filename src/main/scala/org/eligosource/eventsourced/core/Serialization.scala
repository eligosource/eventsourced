package org.eligosource.eventsourced.core

import akka.actor._
import akka.serialization.SerializationExtension

import com.google.protobuf.ByteString

import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.core.JournalProtocol._

class Serialization(system: ExtendedActorSystem) extends Extension {
  val extension = SerializationExtension(system)

  def serializeMessage(message: Message): Array[Byte] =
    messageProtocolBuilder(message).build().toByteArray

  def deserializeMessage(bytes: Array[Byte]): Message =
    message(MessageProtocol.parseFrom(bytes))

  def serializeCommand(command: AnyRef): Array[Byte] = {
    import CommandType._

    val builder = command match {
      case cmd: WriteInMsg => CommandProtocol.newBuilder
        .setCommandType(WRITE_IN)
        .setProcessorId(cmd.processorId)
        .setMessage(messageProtocolBuilder(cmd.message))
      case cmd: WriteOutMsg => CommandProtocol.newBuilder
        .setCommandType(WRITE_OUT)
        .setProcessorId(cmd.ackProcessorId)
        .setChannelId(cmd.channelId)
        .setSequenceNr(cmd.ackSequenceNr)
        .setMessage(messageProtocolBuilder(cmd.message))
      case cmd: WriteAck => CommandProtocol.newBuilder
        .setCommandType(WRITE_ACK)
        .setProcessorId(cmd.processorId)
        .setChannelId(cmd.channelId)
        .setSequenceNr(cmd.ackSequenceNr)
    }
    builder.build().toByteArray()
  }

  def deserializeCommand(bytes: Array[Byte]): AnyRef = {
    import CommandType._

    val commandProtocol = CommandProtocol.parseFrom(bytes)
    commandProtocol.getCommandType match {
      case WRITE_IN => WriteInMsg(
        processorId = commandProtocol.getProcessorId,
        message = message(commandProtocol.getMessage),
        target = null)
      case WRITE_OUT => WriteOutMsg(
        channelId = commandProtocol.getChannelId,
        message = message(commandProtocol.getMessage),
        ackProcessorId = commandProtocol.getProcessorId,
        ackSequenceNr = commandProtocol.getSequenceNr,
        target = null)
      case WRITE_ACK => WriteAck(
        processorId = commandProtocol.getProcessorId,
        channelId = commandProtocol.getChannelId,
        ackSequenceNr = commandProtocol.getSequenceNr)
    }
  }

  private def messageProtocolBuilder(message: Message) = {
    val event = message.event.asInstanceOf[AnyRef]
    val serializer = extension.findSerializerFor(event)
    val builder = MessageProtocol.newBuilder
      .setEvent(ByteString.copyFrom(serializer.toBinary(event)))
      .setEventSerializerId(serializer.identifier)
      .setProcessorId(message.processorId)
      .setSequenceNr(message.sequenceNr)

    message.senderMessageId.foreach { smid =>
      builder.setSenderMessageId(smid)
    }

    if (serializer.includeManifest) {
      builder.setEventManifest(ByteString.copyFromUtf8(event.getClass.getName))
    }

    builder
  }

  private def message(messageProtocol: MessageProtocol): Message = {
    val eventClass = if (messageProtocol.hasEventManifest)
      Some(system.dynamicAccess.getClassFor[AnyRef](messageProtocol.getEventManifest.toStringUtf8).get) else None

    val event = extension.deserialize(
      messageProtocol.getEvent.toByteArray,
      messageProtocol.getEventSerializerId,
      eventClass).get

    val senderMessageId = if (messageProtocol.hasSenderMessageId) Some(messageProtocol.getSenderMessageId) else None

    Message(
      event = event,
      senderMessageId = senderMessageId,
      processorId = messageProtocol.getProcessorId,
      sequenceNr = messageProtocol.getSequenceNr)
  }
}

object Serialization  extends ExtensionId[Serialization] with ExtensionIdProvider {
  override def lookup = Serialization
  override def createExtension(system: ExtendedActorSystem) = new Serialization(system)
}
