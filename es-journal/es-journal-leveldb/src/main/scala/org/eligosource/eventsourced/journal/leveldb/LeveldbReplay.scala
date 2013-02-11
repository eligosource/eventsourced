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
package org.eligosource.eventsourced.journal.leveldb

import akka.actor._

import org.iq80.leveldb._

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.core.Journal._
import org.eligosource.eventsourced.journal.common._

import LeveldbJournalPS._

private [journal] case class LeveldbReplayContext(
  context: ActorContext,
  props: LeveldbJournalProps,
  levelDbReadOptions: ReadOptions,
  leveldb: DB) {

  implicit def actorContext: ActorContext = context
}

private [journal] trait LeveldbReplay {
  val serialization = Serialization(context.actorContext.system)

  def context: LeveldbReplayContext

  def batchReplayInMsgs(sender: ActorRef, cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit)
  def replayInMsgs(sender: ActorRef, cmd: ReplayInMsgs, p: (Message) => Unit)
  def replayOutMsgs(sender: ActorRef, cmd: ReplayOutMsgs, p: (Message) => Unit)

  @scala.annotation.tailrec
  final def confirmingChannelIds(iter: DBIterator, key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (key.processorId         == nextKey.processorId &&
          key.initiatingChannelId == nextKey.initiatingChannelId &&
          key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }
}

private [journal] object LeveldbReplay {
  type ReplayStrategyFactory = (LeveldbReplayContext) => LeveldbReplay

  val defaultReplayStrategy = (context: LeveldbReplayContext) => new DefaultReplay(context)
  val throttledReplayStrategy = (context: LeveldbReplayContext) => new ThrottledReplay(context)
}

private [journal] class DefaultReplay(val context: LeveldbReplayContext) extends LeveldbReplay {
  import LeveldbJournalPS._
  import Journal._
  import context._

  def batchReplayInMsgs(sender: ActorRef, cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    cmds.foreach(cmd => replay(cmd.processorId, 0, cmd.fromSequenceNr, msg => p(msg, cmd.target)))
    sender ! ReplayDone
  }

  def replayInMsgs(sender: ActorRef, cmd: ReplayInMsgs, p: Message => Unit) {
    replay(cmd.processorId, 0, cmd.fromSequenceNr, p)
    sender ! ReplayDone
  }

  def replayOutMsgs(sender: ActorRef, cmd: ReplayOutMsgs, p: Message => Unit) {
    replay(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr, p)
  }

  def replay(processorId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit) {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = Key(processorId, channelId, fromSequenceNr, 0)
      iter.seek(startKey)
      replay(iter, startKey, p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  final def replay(iter: DBIterator, key: Key, p: Message => Unit) {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (just advance iterator)
        replay(iter, nextKey, p)
      } else if (key.processorId         == nextKey.processorId &&
                 key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = serialization.deserializeMessage(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }
}

private [journal] class ThrottledReplay(val context: LeveldbReplayContext) extends LeveldbReplay {
  import context._

  def batchReplayInMsgs(sender: ActorRef, cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) {
    val replay = actor(new ThrottledReplay(cmds, p), dispatcherName = props.dispatcherName)
    replay forward ThrottledReplay.Start
  }

  def replayInMsgs(sender: ActorRef, cmd: ReplayInMsgs, p: Message => Unit) {
    batchReplayInMsgs(sender, List(cmd), (msg, _) => p(msg))
  }

  def replayOutMsgs(sender: ActorRef, cmd: ReplayOutMsgs, p: Message => Unit) {
    val iterator = new LeveldbIterator(Int.MaxValue, cmd.channelId, cmd.fromSequenceNr)
    try { iterator.foreach(p) } finally { iterator.close() }
  }

  private class LeveldbIterator(processorId: Int, channelId: Int, fromSequenceNr: Long) extends Iterator[Message] {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    var key = Key(processorId, channelId, fromSequenceNr, 0)

    iter.seek(key)

    @scala.annotation.tailrec
    final def hasNext = if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = keyFromBytes(nextEntry.getKey)
      if (nextKey.confirmingChannelId != 0) {
        // phantom ack (advance iterator)
        iter.next()
        hasNext
      } else {
        key.processorId         == nextKey.processorId &&
        key.initiatingChannelId == nextKey.initiatingChannelId
      }
    } else false

    def next() = {
      val nextEntry = iter.next()
      val nextKey = keyFromBytes(nextEntry.getKey)
      val msg = serialization.deserializeMessage(nextEntry.getValue)
      val channelIds = confirmingChannelIds(iter, nextKey, Nil)
      key = nextKey
      msg.copy(acks = channelIds)
    }

    def close() {
      iter.close()
    }
  }

  private class ThrottledReplay(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit) extends Actor {
    import context.dispatcher
    import ThrottledReplay._

    val iteratorTargetPairs = cmds.toStream.map {
      case ReplayInMsgs(pid, snr, target) => (new LeveldbIterator(pid, 0, snr), target)
    }

    val messageTargetPairs = for {
      (iterator, target) <- iteratorTargetPairs
      message            <- iterator
    } yield (message, target)

    var initiator: Option[ActorRef] = None
    var todo = messageTargetPairs
    var ctr = 0

    // ---------------------------------------
    // TODO: send failure reply on exception
    // ---------------------------------------

    def receive = {
      case Start => {
        initiator = Some(sender)
        self ! Continue
      }
      case Continue => {
        if (todo.isEmpty) {
          initiator.foreach(_ ! ReplayDone)
          context.stop(self)
        } else if (ctr == props.throttleAfter) {
          ctr = 0
          context.system.scheduler.scheduleOnce(props.throttleFor, self, Continue)
        } else {
          val (message, target) = todo.head
          p(message, target)
          ctr += 1
          todo = todo.tail
          self ! Continue
        }
      }
    }

    override def postStop() {
      iteratorTargetPairs.foreach {
        case (iterator, _) => iterator.close()
      }
    }
  }

  private object ThrottledReplay {
    case object Start
    case object Continue
  }
}
