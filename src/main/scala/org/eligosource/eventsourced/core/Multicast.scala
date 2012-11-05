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

import akka.actor._

/**
 * An [[org.eligosource.eventsourced.core.Eventsourced]] processor that forwards
 * received event [[org.eligosource.eventsourced.core.Message]]s to `targets`
 * (together with the sender reference). Messsages of type other than `Message`
 * are forwarded as well.
 *
 * Using `Multicast` is useful in situtations where mutliple processors should
 * receive the same event messages but an application doesn't want them to journal
 * these messages redundantly.
 *
 * @param targets multicast targets.
 * @param transformer function applied to received event
 *        [[org.eligosource.eventsourced.core.Message]]s before they are forwarded
 *        to `targets`.
 */
class Multicast(targets: Seq[ActorRef], transformer: Message => Any) extends Actor { this: Eventsourced =>
  def receive = {
    case msg: Message => targets.foreach(_ forward transformer(msg))
    case msg          => targets.foreach(_ forward msg)
  }
}
