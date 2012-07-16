/*
 * Copyright 2012 Eligotech BV.
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
package org.eligosource.eventsourced.util

import java.io._

import akka.util.ClassLoaderObjectInputStream

trait Serializer[A] {
  def toBytes(obj: A): Array[Byte]
  def fromBytes(bytes: Array[Byte]): A
}

class JavaSerializer[A] extends Serializer[A] {
  def toBytes(obj: A) = {
    val bos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(obj)
    oos.close()
    bos.toByteArray
  }

  def fromBytes(bytes: Array[Byte]) = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ClassLoaderObjectInputStream(getClass.getClassLoader, bis)
    val obj = ois.readObject()
    ois.close()
    obj.asInstanceOf[A]
  }
}