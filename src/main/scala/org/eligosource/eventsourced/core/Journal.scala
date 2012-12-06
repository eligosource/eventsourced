package org.eligosource.eventsourced.core

import akka.actor._

/**
 * An abstract journal to be implemented by journal providers. The library interacts with a concrete
 * journal via messages defined in the [[org.eligosource.eventsourced.core.Journal$ Journal]] companion
 * object.
 */
trait Journal extends Actor {
  import Channel.Deliver
  import Journal._

  private val deadLetters = context.system.deadLetters
  private var commandListener: Option[ActorRef] = None
  private var _counter = 0L

  /**
   * Handles messages defined in the [[org.eligosource.eventsourced.core.Journal$ Journal]] companion
   * object.
   */
  def receive = {
    case cmd: WriteInMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      executeWriteInMsg(c)
      c.target forward Written(c.message)
      commandListener.foreach(_ ! cmd)
      _counter += 1L
    }
    case cmd: WriteOutMsg => {
      val c = if(cmd.genSequenceNr) cmd.withSequenceNr(counter) else { _counter = cmd.message.sequenceNr; cmd }
      executeWriteOutMsg(c)
      c.target forward Written(c.message)
      commandListener.foreach(_ ! cmd)
      _counter += 1L
    }
    case cmd: WriteAck => {
      executeWriteAck(cmd)
      commandListener.foreach(_ ! cmd)
    }
    case cmd: DeleteOutMsg => {
      executeDeleteOutMsg(cmd)
      commandListener.foreach(_ ! cmd)
    }
    case Loop(msg, target) => {
      target forward (Looped(msg))
    }
    case BatchReplayInMsgs(replays) => {
      executeBatchReplayInMsgs(replays, (msg, target) => target tell (Written(msg), deadLetters))
    }
    case cmd: ReplayInMsgs => {
      executeReplayInMsgs(cmd, msg => cmd.target tell (Written(msg), deadLetters))
    }
    case cmd: ReplayOutMsgs => {
      executeReplayOutMsgs(cmd, msg => cmd.target tell (Written(msg), deadLetters))
    }
    case BatchDeliverOutMsgs(channels) => {
      channels.foreach(_ ! Deliver)
    }
    case SetCommandListener(cl) => {
      commandListener = cl
    }
  }

  /**
   * Initializes the `counter` from the last stored counter value and calls `start()`.
   */
  override def preStart() {
    _counter = storedCounter + 1L
    start()
  }

  /**
   * Calls `stop()`.
   */
  override def postStop() {
    stop()
  }

  /**
   * Returns the current counter value.
   */
  protected def counter = _counter

  /**
   * Returns the last stored counter value.
   */
  protected def storedCounter: Long

  /**
   * Instructs a journal provider to write an input message.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteInMsg]]
   */
  def executeWriteInMsg(cmd: WriteInMsg)

  /**
   * Instructs a journal provider to write an output message,
   * optionally together with an acknowledgement.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteInMsg]]
   */
  def executeWriteOutMsg(cmd: WriteOutMsg)

  /**
   * Instructs a journal provider to write an acknowledgement.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  def executeWriteAck(cmd: WriteAck)

  /**
   * Instructs a journal provider to delete an output message.
   *
   * @param cmd command to be executed by the journal provider.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.DeleteOutMsg]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  def executeDeleteOutMsg(cmd: DeleteOutMsg)

  /**
   * Instructs a journal provider to batch-replay input messages. The provider must
   * reply to the sender with [[org.eligosource.eventsourced.core.Journal.ReplayDone]]
   * when all replayed messages have been added to the corresponding replay targets'
   * mailboxes.
   *
   * @param cmds command batch to be executed by the journal provider.
   * @param p function to be called by the provider for every replayed input message.
   *        The `acks` field of a replayed input message must contain the channel ids
   *        of all acknowledgements for that input message. The replay `target` of the
   *        currently processed [[org.eligosource.eventsourced.core.Journal.ReplayInMsgs]]
   *        command must be passed as second argument.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeBatchReplayInMsgs(cmds: Seq[ReplayInMsgs], p: (Message, ActorRef) => Unit)

  /**
   * Instructs a journal provider to replay input messages. The provider must
   * reply to the sender with [[org.eligosource.eventsourced.core.Journal.ReplayDone]]
   * when all replayed messages have been added to the corresponding replay target's
   * mailbox.
   *
   * @param cmd command to be executed by the journal provider.
   * @param p function to be called by the provider for each replayed input message.
   *        The `acks` field of a replayed input message must contain the channel ids
   *        of all acknowledgements for that input message.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.WriteAck]]
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeReplayInMsgs(cmd: ReplayInMsgs, p: Message => Unit)

  /**
   * Instructs a journal provider to replay output messages.
   *
   * @param cmd command to be executed by the journal provider.
   * @param p function to be called by the provider for each replayed output message.
   *
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   */
  def executeReplayOutMsgs(cmd: ReplayOutMsgs, p: Message => Unit)

  /**
   * Start callback. Empty default implementation.
   */
  protected def start() = {}

  /**
   * Stop callback. Empty default implementation.
   */
  protected def stop() = {}
}

/**
 * Defines message types that can be processed by a [[org.eligosource.eventsourced.core.Journal]].
 */
object Journal {
  val SkipAck: Long = -1L

  /**
   * Creates a journal actor from the specified journal configuration object.
   *
   * @param props journal configuration object.
   * @return journal actor.
   */
  def apply(props: JournalProps)(implicit actorRefFactory: ActorRefFactory): ActorRef =
    actor(props.journal, props.name, props.dispatcherName)

  /**
   * Instructs a `Journal` to write an input `message`. An input message is an event message
   * sent to an `Eventsourced` processor.
   *
   * @param processorId id of the `Eventsourced` processor.
   * @param message input message.
   * @param target target that should receive the input message after it has been written.
   *        The input message is sent to `target` wrapped in
   *        [[org.eligosource.eventsourced.core.Journal.Written]]. The sender reference is
   *        set to `system.deadLetters`.
   * @param genSequenceNr `true` if `message.sequenceNr` should be updated to the journal's
   *        current `counter` value or `false` if the journal's `counter` should be set to
   *        `message.sequenceNr`.
   */
  case class WriteInMsg(processorId: Int, message: Message, target: ActorRef, genSequenceNr: Boolean = true) {
    def withSequenceNr(snr: Long) = copy(message = message.copy(sequenceNr = snr), genSequenceNr = false)
  }

  /**
   * Instructs a `Journal` to write an output `message`. An output message is an event message
   * sent to a `ReliableChannel`. Together with the output message, an acknowledgement can
   * optionally be written. The acknowledgement refers to the input message that caused the
   * emission of the output `message`. Refer to [[org.eligosource.eventsourced.core.Journal.WriteAck]]
   * for more details about acknowledgements.
   *
   * @param channelId id of the reliable channel.
   * @param message output message.
   * @param ackProcessorId id of the `Eventsourced` processor that emitted the output
   *        message to the reliable channel.
   * @param ackSequenceNr sequence number of the input message that caused the emission
   *        of the output `message`.
   * @param target target that should receive the output message after it has been written.
   *        The output message is sent to `target` wrapped in
   *        [[org.eligosource.eventsourced.core.Journal.Written]]. The sender reference is
   *        set to `system.deadLetters`.
   * @param genSequenceNr `true` if `message.sequenceNr` should be updated to the journal's
   *        current `counter` value or `false` if the journal's `counter` should be set to
   *        `message.sequenceNr`.
   */
  case class WriteOutMsg(channelId: Int, message: Message, ackProcessorId: Int, ackSequenceNr: Long, target: ActorRef, genSequenceNr: Boolean = true) {
    def withSequenceNr(snr: Long) = copy(message = message.copy(sequenceNr = snr), genSequenceNr = false)
  }

  /**
   * Instructs a `Journal` to write an acknowledgement. An acknowledgement refers to a previously
   * written input `Message` and a `Channel`. Output `Message`s (that an `Eventsourced` processor
   * emits to a channel during processing of that input message) are ignored by that channel if an
   * acknowledgement exists for that input message and channel. This mechanism prevents redundant
   * message delivery to channel destinations during event message replay.
   *
   * @param processorId id of the `Eventsourced` processor that emitted the output message to the
   *        channel.
   * @param channelId id of the channel that received the output message from the `Eventsourced`
   *        processor.
   * @param ackSequenceNr sequence number of the input message.
   *
   * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  case class WriteAck(processorId: Int, channelId: Int, ackSequenceNr: Long)

  /**
   * Instructs a `Journal` to delete an output message that has been previously written by a
   * `ReliableChannel`.
   *
   * @param channelId id of the reliable channel.
   * @param msgSequenceNr sequence number of the output message.
   *
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  case class DeleteOutMsg(channelId: Int, msgSequenceNr: Long)

  /**
   * Instructs a `Journal` to initiate message delivery for `channels`.
   *
   * @param channels channels for which message delivery should be initiated.
   *
   * @see [[org.eligosource.eventsourced.core.EventsourcingExtension]]
   * @see [[org.eligosource.eventsourced.core.DefaultChannel]]
   * @see [[org.eligosource.eventsourced.core.ReliableChannel]]
   */
  case class BatchDeliverOutMsgs(channels: Seq[ActorRef])

  /**
   * Instructs a `Journal` to batch-replay input messages to multiple `Eventsourced` processors.
   *
   * @param replays command batch.
   *
   * @see [[org.eligosource.eventsourced.core.Journal.ReplayInMsgs]]
   */
  case class BatchReplayInMsgs(replays: Seq[ReplayInMsgs])

  /**
   * Instructs a `Journal` to replay input messages to a single `Eventsourced` processor.
   *
   * @param processorId id of the `Eventsourced` processor.
   * @param fromSequenceNr sequence number from where the replay should start.
   * @param target receiver of the replayed messages. The journal sends the
   *        replayed messages to `target` wrapped in a
   *        [[org.eligosource.eventsourced.core.Journal.Written]] message. The
   *        sender reference is set to `system.deadLetters`.
   */
  case class ReplayInMsgs(processorId: Int, fromSequenceNr: Long, target: ActorRef)

  /**
   * Instructs a `Journal` to replay output messages for a single `ReliableChannel`.
   *
   * @param channelId id of the reliable channel.
   * @param fromSequenceNr sequence number from where the replay should start.
   * @param target receiver of the replayed messages. The journal sends the
   *        replayed messages to `target` wrapped in a
   *        [[org.eligosource.eventsourced.core.Journal.Written]] message. The
   *        sender reference is set to `system.deadLetters`.
   */
  case class ReplayOutMsgs(channelId: Int, fromSequenceNr: Long, target: ActorRef)

  /**
   * Response from a journal to a sender when input message replay has been completed.
   */
  case object ReplayDone

  /**
   * Instructs a `Journal` to forward `msg` to `target` wrapped in a
   * [[org.eligosource.eventsourced.core.Journal.Looped]] message.
   *
   * @param msg
   * @param target
   */
  case class Loop(msg: Any, target: ActorRef)

  /**
   * Message wrapper used by the [[org.eligosource.eventsourced.core.Journal.Loop]]
   * command.
   *
   * @param msg wrapped message.
   */
  case class Looped(msg: Any)

  /**
   * Event message wrapper used by the
   * [[org.eligosource.eventsourced.core.Journal.WriteInMsg]],
   * [[org.eligosource.eventsourced.core.Journal.WriteOutMsg]],
   * [[org.eligosource.eventsourced.core.Journal.BatchReplayInMsgs]],
   * [[org.eligosource.eventsourced.core.Journal.ReplayInMsgs]] and
   * [[org.eligosource.eventsourced.core.Journal.ReplayOutMsgs]] commands.
   *
   * @param msg wrapped event message.
   */
  case class Written(msg: Message)

  private [eventsourced] case class SetCommandListener(listener: Option[ActorRef])
}