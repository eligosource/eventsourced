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
package org.eligosource.eventsourced.patterns

import akka.actor._

import org.eligosource.eventsourced.core._

case class SetScatterSource(source: ActorRef)

case class SuccessReply(msg: Any, targetId: Int)
case class FailureReply(msg: Any, targetId: Int)
case class Scatter(msg: Any, targetId: Int) {
  def successReply(msg: Any) = SuccessReply(msg, targetId)
  def failureReply(msg: Any) = FailureReply(msg, targetId)
}

case class GatheredSuccess(target: ActorRef, msg: Any, replyMsg: Any)
case class GatheredFailure(target: ActorRef, msg: Any, replyMsg: Any)
case class Scattered(target: ActorRef, msg: Any) {
  def handle(reply: SuccessReply) = GatheredSuccess(target, msg, reply.msg)
  def handle(reply: FailureReply) = GatheredFailure(target, msg, reply.msg)
}

trait GatherResult {
  def gatheredSuccess: Seq[GatheredSuccess]
  def gatheredFailure: Seq[GatheredFailure]
}

private [patterns] case class ScatterGatherState(
  scattered: Map[Int, Scattered] = Map.empty,
  gatheredSuccess: List[GatheredSuccess] = Nil,
  gatheredFailure: List[GatheredFailure] = Nil) extends GatherResult {

  def handle(sr: SuccessReply): ScatterGatherState = scattered.get(sr.targetId) match {
    case Some(r) => copy(scattered = scattered - sr.targetId, gatheredSuccess = r.handle(sr) :: gatheredSuccess)
    case None    => this
  }

  def handle(fr: FailureReply): ScatterGatherState = scattered.get(fr.targetId) match {
    case Some(r) => copy(scattered = scattered - fr.targetId, gatheredFailure = r.handle(fr) :: gatheredFailure)
    case None    => this
  }

  def isCompleted = scattered.isEmpty
}

private [patterns] object ScatterGatherState {
  def apply(targetedMessages: Iterable[(ActorRef, Any)]) = {
    val rs = targetedMessages.zipWithIndex.map { case ((target, msg), idx) => idx -> Scattered(target, msg) }
    new ScatterGatherState(rs.toMap)
  }
}

class ScatterGather(processor: Receiver, targetedMessages: Iterable[(ActorRef, Any)]) {
  private var state = ScatterGatherState(targetedMessages)

  type Handler = GatherResult => Unit

  def scatter() {
    state.scattered.foreach {
      case (targetId, Scattered(target, msg)) => {
        target ! processor.message.copy(Scatter(msg, targetId))
      }
    }
  }

  def gather(onComplete: Handler) {
    processor.become(gathering(onComplete))
  }

  private def gathering(handler: Handler): Actor.Receive = {
    case sr: SuccessReply => {
      state = state.handle(sr)
      processor.confirm()
      if (state.isCompleted) handler(state)
    }
    case fr: FailureReply => {
      state = state.handle(fr)
      processor.confirm()
      if (state.isCompleted) handler(state)
    }
  }
}

trait ScatterTarget extends Actor { this: Receiver =>
  var scatterSource: Option[ActorRef] = None

  var lastSequenceNr = 0L
  var redeliveries = 0

  def scattered(msg: Any, redeliveries: Int, onSuccess: Any => Unit, onFailure: Any => Unit)

  def receive = {
    case s: Scatter => {
      val current = message

      val onSuccess = (msg: Any) => scatterSource.foreach(_ ! current.copy(s.successReply(msg)))
      val onFailure = (msg: Any) => scatterSource.foreach(_ ! current.copy(s.failureReply(msg)))

      if (current.sequenceNr == lastSequenceNr) redeliveries += 1 else {
        lastSequenceNr = current.sequenceNr
        redeliveries = 0
      }

      scattered(s.msg, redeliveries, onSuccess, onFailure)
    }
    case SetScatterSource(source: ActorRef) => {
      this.scatterSource = Some(source)
    }
  }
}