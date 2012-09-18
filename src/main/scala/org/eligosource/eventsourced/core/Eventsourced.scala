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
package org.eligosource.eventsourced.core

/**
 * Stackable modification for actors that makes them persistent using
 * event (or command) sourcing.
 */
trait Eventsourced extends Emitter {
  private var _id: Int = _

  def id = _id

  abstract override def receive = {
    case SetId(id) => {
      _id = id
    }
    case cmd: SetContext => {
      super.receive(cmd)
    }
    case msg: Message if (sender == journal) => {
      super.receive(msg.copy(processorId = id))
    }
    case msg: Message /* received from channel or any other sender */ => {
      journal forward WriteInMsg(id, msg, self)
    }
    case msg /* any other message type bypasses journaling */ => {
      super.receive(msg)
    }
  }
}
