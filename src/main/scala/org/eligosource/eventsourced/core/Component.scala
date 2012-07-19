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
 * to communicate with other parts of the application. Components can be composed
 * to directed, cyclic graphs.
 */
class Component(val id: Int, val journaler: ActorRef)(implicit system: ActorSystem) extends Iterable[Component] {
  import Channel._
  import Journaler._

  import ReliableOutputChannel.{
    defaultRecoveryDelay => rcd,
    defaultRetryDelay    => rtd,
    defaultRetryMax      => rtm
  }

  val inputChannel = system.actorOf(Props(new InputChannel(id, journaler)))
  val inputProducer = system.actorOf(Props(new InputChannelProducer(inputChannel)))

  private var outputChannels = Map.empty[String, ActorRef]
  private var outputDependencies = List.empty[Component]
  private var processor: Option[ActorRef] = None

  def addReliableOutputChannelToSelf(name: String): Component = {
    addReliableOutputChannelToActor(name, inputChannel)
  }

  def addDefaultOutputChannelToSelf(name: String): Component = {
    addDefaultOutputChannelToActor(name, inputChannel)
  }

  def addReliableOutputChannelToActor(name: String, destination: ActorRef, recoveryDelay: Duration  = rcd, retryDelay: Duration = rtd, retryMax: Int = rtm): Component = {
    checkAddChannelPreconditions()
    outputChannels = outputChannels + (name -> createReliableOutputChannel(destination, recoveryDelay, retryDelay, retryMax))
    this
  }

  def addReliableOutputChannelToComponent(name: String, component: Component, recoveryDelay: Duration  = rcd, retryDelay: Duration = rtd, retryMax: Int = rtm): Component = {
    checkAddChannelPreconditions()
    outputChannels = outputChannels + (name -> createReliableOutputChannel(component.inputChannel, recoveryDelay, retryDelay, retryMax))
    outputDependencies = component :: outputDependencies
    this
  }

  def addDefaultOutputChannelToActor(name: String, destination: ActorRef): Component = {
    checkAddChannelPreconditions()
    outputChannels = outputChannels + (name -> createDefaultOutputChannel(destination))
    this
  }

  def addDefaultOutputChannelToComponent(name: String, component: Component): Component = {
    checkAddChannelPreconditions()
    outputChannels = outputChannels + (name -> createDefaultOutputChannel(component.inputChannel))
    outputDependencies = component :: outputDependencies
    this
  }

  def setProcessor(processorFactory: Map[String, ActorRef] => ActorRef): Component = {
    checkSetProcessorPreconditions()
    processor = Some(processorFactory(outputChannels))
    inputChannel ! Channel.SetProcessor(processor.get)
    this
  }

  /**
   * Recovers processor state by replaying input events.
   */
  def replay(fromSequenceNr: Long = 0L, duration: Duration = 5 seconds): Unit = processor foreach { p =>
    Await.result(journaler.ask(Replay(id, inputChannelId, fromSequenceNr, p))(duration), duration)
  }

  /**
   * Delivers pending messages from output channels.
   */
  def deliver(): Unit = processor foreach { _ =>
    outputChannels.values.foreach(_ ! Deliver)
  }

  /**
   * Returns an iterator for this component's dependency graph. Dependent components
   * are visited once even if the dependency graph is cyclic.
   */
  def iterator: Iterator[Component] = {
    traverse(this, Nil).reverse.iterator
  }

  private def traverse(component: Component, visited: List[Component]): List[Component] = {
    component.outputDependencies.foldLeft(component :: visited){ (a, d) =>
    // small number of dependencies i.e. O(n) 'contains' is ok here
      if (!a.contains(d)) traverse(d, a) else a
    }
  }

  private def createDefaultOutputChannel(destination: ActorRef) = {
    val channelId = outputChannels.size + 1
    val channel = system.actorOf(Props(new DefaultOutputChannel(id, channelId, journaler)))
    channel ! Channel.SetDestination(destination)
    channel
  }

  private def createReliableOutputChannel(destination: ActorRef, recoveryDelay: Duration, retryDelay: Duration, retryMax: Int) = {
    val channelId = outputChannels.size + 1
    val channelEnv = ReliableOutputChannelEnv(id, journaler, recoveryDelay, retryDelay, retryMax)
    val channel = system.actorOf(Props(new ReliableOutputChannel(channelId, channelEnv)))
    channel ! Channel.SetDestination(destination)
    channel
  }

  private def checkAddChannelPreconditions() {
    if (processor.isDefined) throw new IllegalStateException("output channels cannot be added after processor has been set")
  }

  private def checkSetProcessorPreconditions() {
    if (processor.isDefined) throw new IllegalStateException("processor can only be set once")
  }
}

object Component {
  def apply(id: Int, journaler: ActorRef)(implicit system: ActorSystem): Component =
    new Component(id, journaler)

  def apply(id: Int, journalDir: File)(implicit system: ActorSystem): Component =
    apply(id, system.actorOf(Props(new Journaler(journalDir))))

  val invalidStateMessage = "output channels cannot be added after processor has been set"
}