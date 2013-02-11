package org.eligosource.eventsourced.core

import akka.actor._

/**
 * Journal configuration object.
 */
trait JournalProps {
  /**
   * Optional journal name.
   */
  def name: Option[String]

  /**
   * Optional dispatcher name.
   */
  def dispatcherName: Option[String]

  /**
   * Creates a journal actor instance.
   */
  def journal: Actor
}

/**
 * Defines message types that can be processed by a journal actor.
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
    def withTimestamp = copy(message = message.withTimestamp)
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
   * Response from a journal when message delivery by channels has been initiated.
   */
  case object DeliveryDone

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
   * Message sent to targets after processing
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