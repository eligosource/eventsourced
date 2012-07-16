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

import java.io.File

import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.util.Duration
import akka.util.duration._

/**
 * An event-sourced component that uses an application-defined actor (processor)
 * for managing state. Applications use the component's input channel to send
 * event messages to that actor. The actor uses the component's output channel
 * to communicate with other parts of the application.
 */
trait Component {
  import Channel._
  import Journaler._

  def id: Int

  def inputChannel: ActorRef
  def outputChannels: Map[String, ActorRef]

  val producer: ActorRef

  def processor: ActorRef
  def journaler: ActorRef

  /**
   * Replays event messages that have been sent to this component. Messages that
   * have already been sent to destinations via output channels are not sent again
   * during replay. Messages that have not yet been sent to destinations will be
   * sent during replay. The returned Future completes when all messages to be
   * replayed have been added to the processor's mailbox.
   */
  def replay(fromSequenceNr: Long = 0L, duration: Duration = 5 seconds): Future[Unit] =
    journaler.ask(Replay(id, inputChannelId, fromSequenceNr, processor))(duration).mapTo[Unit]

  /**
   * Delivers messages, which have been stored by reliable output channels, to their
   * destinations.
   */
  def deliver: Unit =
    throw new UnsupportedOperationException("not implemented yet")

  /**
   * Delivers and replays event messages. Called when the component is initialized.
   */
  def recover: Unit =
    throw new UnsupportedOperationException("not implemented yet")
}

/**
 * A Component builder.
 */
case class ComponentBuilder(
    componentId: Int,
    journaler: ActorRef,
    inputChannel: ActorRef,
    outputChannels: Map[String, ActorRef])(implicit system: ActorSystem) { outer =>

  def addReliableOutputChannel(name: String, destination: ActorRef): ComponentBuilder = {
    val channel = system.actorOf(Props(new ReliableOutputChannel(componentId, outputChannels.size + 1, journaler)))
    channel ! Channel.SetDestination(destination)
    copy(outputChannels = outputChannels + (name -> channel))
  }

  def addDefaultOutputChannel(name: String, destination: ActorRef): ComponentBuilder = {
    val channel = system.actorOf(Props(new DefaultOutputChannel(componentId, outputChannels.size + 1, journaler)))
    channel ! Channel.SetDestination(destination)
    copy(outputChannels = outputChannels + (name -> channel))
  }

  def addDefaultOutputChannel(name: String, component: Component): ComponentBuilder = {
    addDefaultOutputChannel(name, component.inputChannel)
  }

  def addSelfOutputChannel(name: String): ComponentBuilder = {
    addDefaultOutputChannel(name, inputChannel)
  }

  def setProcessor(processorFactory: Map[String, ActorRef] => ActorRef): Component = {
    val proc = processorFactory(outputChannels)
    inputChannel ! Channel.SetProcessor(proc)
    new Component {
      val id = componentId

      val inputChannel = outer.inputChannel
      val outputChannels = outer.outputChannels

      val producer = system.actorOf(Props(new InputChannelProducer(inputChannel)))

      val processor = proc
      val journaler = outer.journaler
    }
  }
}

object ComponentBuilder {
  def apply(componentId: Int, journalDir: File)(implicit system: ActorSystem): ComponentBuilder = {
    val journaler = system.actorOf(Props(new Journaler(journalDir)))
    apply(componentId, journaler)
  }
  def apply(componentId: Int, journaler: ActorRef)(implicit system: ActorSystem): ComponentBuilder = {
    val inputChannel = system.actorOf(Props(new InputChannel(componentId, journaler)))
    ComponentBuilder(componentId, journaler, inputChannel, Map.empty)
  }
}
