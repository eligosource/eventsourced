/*
 * Copyright 2012-2013 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.journal.common.serialization

import scala.language.existentials

import akka.actor._
import akka.serialization.{Serializer, Serialization, SerializationExtension}

import com.google.protobuf.ByteString

import org.eligosource.eventsourced.core.{Confirmation, Message}
import org.eligosource.eventsourced.core.JournalProtocol._
import org.eligosource.eventsourced.journal.common.serialization.Protocol._

/**
 * Extension for protobuf-based (de)serialization of confirmation messages.
 *
 * @see [[org.eligosource.eventsourced.core.Confirmation]]
 */
class ConfirmationSerialization(system: ExtendedActorSystem) extends Extension {
  def serializeConfirmation(confirmation: Confirmation): Array[Byte] =
    confirmationProtocolBuilder(confirmation).build().toByteArray

  def deserializeConfirmation(bytes: Array[Byte]): Confirmation =
    confirmation(ConfirmationProtocol.parseFrom(bytes))

  protected def confirmationProtocolBuilder(confirmation: Confirmation) = ConfirmationProtocol.newBuilder
    .setProcessorId(confirmation.processorId)
    .setChannelId(confirmation.channelId)
    .setSequenceNr(confirmation.sequenceNr)
    .setPositive(confirmation.positive)

  protected def confirmation(confirmationProtocol: ConfirmationProtocol): Confirmation = Confirmation(
    confirmationProtocol.getProcessorId,
    confirmationProtocol.getChannelId,
    confirmationProtocol.getSequenceNr,
    confirmationProtocol.getPositive)
}

/**
 * Extension for protobuf-based (de)serialization of event messages.
 * Serializers for events contained in event messages are looked up
 * in the Akka [[akka.serialization.Serialization]] extension.
 *
 * @see [[org.eligosource.eventsourced.core.Message]]
 */
class MessageSerialization(system: ExtendedActorSystem) extends ConfirmationSerialization(system) {
  val extension = SerializationExtension(system)

  /**
   * Serializes an event [[org.eligosource.eventsourced.core.Message]].
   *
   * @param message event message.
   * @return serialized event message.
   */
  def serializeMessage(message: Message): Array[Byte] =
    messageProtocolBuilder(message).build().toByteArray

  /**
   * Deserializes an event [[org.eligosource.eventsourced.core.Message]].
   *
   * @param bytes serialized event message.
   * @return event message.
   */
  def deserializeMessage(bytes: Array[Byte]): Message =
    message(MessageProtocol.parseFrom(bytes))

  protected def messageProtocolBuilder(message: Message) = {
    val event = message.event.asInstanceOf[AnyRef]
    val serializer = extension.findSerializerFor(event)
    val builder = MessageProtocol.newBuilder
      .setEvent(ByteString.copyFrom(serializer.toBinary(event)))
      .setEventSerializerId(serializer.identifier)
      .setProcessorId(message.processorId)
      .setSequenceNr(message.sequenceNr)
      .setTimestamp(message.timestamp)

    if (message.confirmationTarget != null && message.confirmationPrototype != null) {
      builder.setConfirmationTarget(Serialization.serializedActorPath(message.confirmationTarget))
      builder.setConfirmationPrototype(confirmationProtocolBuilder(message.confirmationPrototype))
    }

    if (message.senderRef != null) {
      builder.setSenderRef(Serialization.serializedActorPath(message.senderRef))
    }

    if (serializer.includeManifest) {
      builder.setEventManifest(ByteString.copyFromUtf8(event.getClass.getName))
    }

    builder
  }

  protected def message(messageProtocol: MessageProtocol): Message = {
    val eventClass = if (messageProtocol.hasEventManifest)
      Some(system.dynamicAccess.getClassFor[AnyRef](messageProtocol.getEventManifest.toStringUtf8).get) else None

    val event = extension.deserialize(
      messageProtocol.getEvent.toByteArray,
      messageProtocol.getEventSerializerId,
      eventClass).get

    val confirmationTarget =
      if (messageProtocol.hasConfirmationTarget) system.provider.resolveActorRef(messageProtocol.getConfirmationTarget) else null

    val confirmationPrototype =
      if (messageProtocol.hasConfirmationPrototype) confirmation(messageProtocol.getConfirmationPrototype) else null

    val senderRef =
      if (messageProtocol.hasSenderRef) system.provider.resolveActorRef(messageProtocol.getSenderRef) else null

    Message(
      event = event,
      processorId = messageProtocol.getProcessorId,
      sequenceNr = messageProtocol.getSequenceNr,
      timestamp = messageProtocol.getTimestamp,
      confirmationTarget = confirmationTarget,
      confirmationPrototype = confirmationPrototype,
      senderRef = senderRef)
  }
}

/**
 * Extension for protobuf-based (de)serialization of journal commands.
 * Serializers for events contained in event messages are looked up
 * in the Akka [[akka.serialization.Serialization]] extension.
 */
class CommandSerialization(system: ExtendedActorSystem) extends MessageSerialization(system) {
  /**
   * Serializes journal commands.
   *
   *  - [[org.eligosource.eventsourced.core.JournalProtocol.WriteInMsg]]
   *  - [[org.eligosource.eventsourced.core.JournalProtocol.WriteOutMsg]]
   *  - [[org.eligosource.eventsourced.core.JournalProtocol.WriteAck]]
   *
   * @param command journal command.
   * @return serialized journal command.
   */
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

  /**
   * Deserializes journal commands.
   *
   *  - [[org.eligosource.eventsourced.core.JournalProtocol.WriteInMsg]]
   *  - [[org.eligosource.eventsourced.core.JournalProtocol.WriteOutMsg]]
   *  - [[org.eligosource.eventsourced.core.JournalProtocol.WriteAck]]
   *
   * @param bytes serialized journal command.
   * @return journal command.
   */
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
}

object ConfirmationSerialization  extends ExtensionId[ConfirmationSerialization] with ExtensionIdProvider {
  override def lookup = ConfirmationSerialization
  override def createExtension(system: ExtendedActorSystem) = new ConfirmationSerialization(system)
}

object MessageSerialization  extends ExtensionId[MessageSerialization] with ExtensionIdProvider {
  override def lookup = MessageSerialization
  override def createExtension(system: ExtendedActorSystem) = new MessageSerialization(system)
}

object CommandSerialization  extends ExtensionId[CommandSerialization] with ExtensionIdProvider {
  override def lookup = CommandSerialization
  override def createExtension(system: ExtendedActorSystem) = new CommandSerialization(system)
}

/**
 * Protobuf-based [[org.eligosource.eventsourced.core.Confirmation]] serialize. The
 * Eventsourced library configures this serializer as default serializer for confirmation
 * messages.
 */
class ConfirmationSerializer(system: ExtendedActorSystem) extends Serializer {
  lazy val serialization = ConfirmationSerialization(system)

  /**
   * Returns `43872`.
   */
  def identifier = 43872

  /**
   * Returns `false`.
   */
  def includeManifest = false

  /**
   * Serializes a [[org.eligosource.eventsourced.core.Confirmation]] message.
   *
   * @param msg confirmation message.
   * @return serialized confirmation message.
   */
  def toBinary(msg: AnyRef) = msg match {
    case c: Confirmation => serialization.serializeConfirmation(c)
    case _               => throw new IllegalArgumentException("Cannot serialize %s" format msg.getClass)
  }

  /**
   * Deserializes a [[org.eligosource.eventsourced.core.Confirmation]] message.
   *
   * @param bytes serialized confirmation message.
   * @return deserialized confirmation message.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) =
    serialization.deserializeConfirmation(bytes)
}

/**
 * Protobuf-based [[org.eligosource.eventsourced.core.Message]] serializer that uses
 * the Akka [[akka.serialization.Serialization]] extension to find a serializer for
 * an event contained in an event message. The Eventsourced library configures this
 * serializer as default serializer for event messages.
 */
class MessageSerializer(system: ExtendedActorSystem) extends Serializer {
  lazy val serialization = MessageSerialization(system)

  /**
   * Returns `43871`.
   */
  def identifier = 43871

  /**
   * Returns `true`.
   */
  def includeManifest = true

  /**
   * Serializes an event [[org.eligosource.eventsourced.core.Message]].
   *
   * @param msg event message.
   * @return serialized event message.
   */
  def toBinary(msg: AnyRef) = msg match {
    case m: Message => serialization.serializeMessage(m)
    case _          => throw new IllegalArgumentException("Cannot serialize %s" format msg.getClass)
  }

  /**
   * Deserializes an event [[org.eligosource.eventsourced.core.Message]].
   *
   * @param bytes serialized event message.
   * @param manifest event message manifest.
   * @return deserialized event message.
   */
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) = manifest match {
    case Some(c) if (c == classOf[Message]) => serialization.deserializeMessage(bytes)
    case Some(c) => throw new IllegalArgumentException("Cannot deserialize %s" format c)
    case None    => throw new IllegalArgumentException("Manifest not available")
  }
}

