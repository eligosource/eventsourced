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
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.Timeout

class Initiator(processor: ActorRef) {
  def ??(msg: Message)(implicit timeout: Timeout, context: Context): Future[Any] =
    context.producer.ask(Producer.Produce(msg, processor))
}

private [core] class Producer extends Actor {
  import Producer._

  def receive = {
    case Produce(msg: Message, target) => {
      target ! msg.copy(sender = Some(sender))
    }
  }
}

private [core] object Producer {
  case class Produce(msg: Message, target: ActorRef)
}

