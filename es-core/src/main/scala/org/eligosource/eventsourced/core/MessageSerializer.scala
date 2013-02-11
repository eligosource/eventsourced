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
package org.eligosource.eventsourced.core

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializer

/**
 * Protobuf-based [[org.eligosource.eventsourced.core.Message]] serializer that uses
 * the Akka [[akka.serialization.Serialization]] extension to find a serializer for
 * an event contained in an event message. The eventsourced library configures this
 * serializer as default serializer for event messages.
 */
class MessageSerializer(system: ExtendedActorSystem) extends Serializer {
  lazy val serialization = Serialization(system)

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
    case Some(c) if (c == classOf[Message]) =>  serialization.deserializeMessage(bytes)
    case Some(c) => throw new IllegalArgumentException("Cannot deserialize %s" format c)
    case None    => throw new IllegalArgumentException("Manifest not available")
  }
}
