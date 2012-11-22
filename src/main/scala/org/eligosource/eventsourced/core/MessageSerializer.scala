package org.eligosource.eventsourced.core

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializer

class MessageSerializer(system: ExtendedActorSystem) extends Serializer {
  val serialization = Serialization(system)

  def identifier = 100
  def includeManifest = true

  def toBinary(o: AnyRef) = o match {
    case m: Message => serialization.serializeMessage(m)
    case _          => throw new IllegalArgumentException("Cannot serialize %s" format o.getClass)
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) = manifest match {
    case Some(c) if (c == classOf[Message]) =>  serialization.deserializeMessage(bytes)
    case Some(c) => throw new IllegalArgumentException("Cannot deserialize %s" format c)
    case None    => throw new IllegalArgumentException("Manifest not available")
  }
}
