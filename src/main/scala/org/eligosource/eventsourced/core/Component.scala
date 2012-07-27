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
import akka.util.Duration

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

  private var inputProcessor: Option[ActorRef] = None
  private var outputDependencies = List.empty[Component]
  private var outputChannelsForName = Map.empty[String, ActorRef]
  private var outputChannelsForId = Map.empty[Int, ActorRef]

  def addReliableOutputChannelToActor(name: String, destination: ActorRef, recoveryDelay: Duration = rcd, retryDelay: Duration = rtd, retryMax: Int = rtm): Component = {
    addReliableOutputChannel(name, destination, recoveryDelay, retryDelay, retryMax)
  }

  def addReliableOutputChannelToComponent(name: String, component: Component, recoveryDelay: Duration = rcd, retryDelay: Duration = rtd, retryMax: Int = rtm): Component = {
    outputDependencies = component :: outputDependencies
    addReliableOutputChannel(name, component.inputChannel, recoveryDelay, retryDelay, retryMax)
  }

  def addReliableOutputChannelToSelf(name: String, recoveryDelay: Duration = rcd, retryDelay: Duration = rtd, retryMax: Int = rtm): Component = {
    addReliableOutputChannelToActor(name, inputChannel, recoveryDelay, retryDelay, retryMax)
  }

  def addDefaultOutputChannelToActor(name: String, destination: ActorRef): Component = {
    addDefaultOutputChannel(name, destination)
  }

  def addDefaultOutputChannelToComponent(name: String, component: Component): Component = {
    outputDependencies = component :: outputDependencies
    addDefaultOutputChannel(name, component.inputChannel)
  }

  def addDefaultOutputChannelToSelf(name: String): Component = {
    addDefaultOutputChannelToActor(name, inputChannel)
  }

  def setProcessor(processorFactory: Map[String, ActorRef] => ActorRef): Component = {
    checkSetProcessorPreconditions()
    inputProcessor = Some(processorFactory(outputChannelsForName))
    inputChannel ! Channel.SetProcessor(inputProcessor.get)
    this
  }

  def processor: Option[ActorRef] =
    inputProcessor

  /**
   * Initializes this component, recovering from existing journal data if necessary.
   */
  def init(fromSequenceNr: Long = 0L): Unit = {
    recount()
    replay(fromSequenceNr)
    deliver()
  }

  /**
   * Synchronizes message counters of channels with the journal.
   */
  def recount(): Unit = {
    // set counter on input channel
    journaler ! Recount(this.id, Channel.inputChannelId, count => inputChannel ! SetCounter(count + 1))

    // set counter on reliable output channels
    for ((id, ch)  <- outputChannelsForId) journaler ! Recount(this.id, id, count => ch ! SetCounter(count + 1))
  }

  /**
   * Recovers processor state by replaying input events.
   */
  def replay(fromSequenceNr: Long = 0L): Unit = inputProcessor foreach { p =>
    journaler ! Replay(id, inputChannelId, fromSequenceNr, p)
  }

  /**
   * Initializes output channels and delivers pending messages, if needed.
   */
  def deliver(): Unit = inputProcessor foreach { _ =>
    outputChannelsForName.values.foreach(_ ! Deliver)
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

  private def addDefaultOutputChannel(name: String, destination: ActorRef) = {
    checkAddChannelPreconditions()

    val channelId = outputChannelsForName.size + 1
    val channel = system.actorOf(Props(new DefaultOutputChannel(id, channelId, journaler)))

    channel ! Channel.SetDestination(destination)

    outputChannelsForName = outputChannelsForName + (name -> channel)

    this
  }

  private def addReliableOutputChannel(name: String, destination: ActorRef, recoveryDelay: Duration, retryDelay: Duration, retryMax: Int) = {
    checkAddChannelPreconditions()

    val channelId = outputChannelsForName.size + 1
    val channelEnv = ReliableOutputChannelEnv(id, journaler, recoveryDelay, retryDelay, retryMax)
    val channel = system.actorOf(Props(new ReliableOutputChannel(channelId, channelEnv)))

    channel ! Channel.SetDestination(destination)

    outputChannelsForName = outputChannelsForName + (name -> channel)
    outputChannelsForId = outputChannelsForId + (channelId -> channel)

    this
  }

  private def checkAddChannelPreconditions() {
    if (inputProcessor.isDefined) throw new IllegalStateException("output channels cannot be added after processor has been set")
  }

  private def checkSetProcessorPreconditions() {
    if (inputProcessor.isDefined) throw new IllegalStateException("processor can only be set once")
  }
}

object Component {
  def apply(id: Int, journaler: ActorRef)(implicit system: ActorSystem): Component =
    new Component(id, journaler)

  def apply(id: Int, journalDir: File)(implicit system: ActorSystem): Component =
    apply(id, system.actorOf(Props(new Journaler(journalDir))))

  val invalidStateMessage = "output channels cannot be added after processor has been set"
}

object Composite {
  def init(composite: Component) = {
    recount(composite)
    replay(composite)
    deliver(composite)
  }

  def recount(composite: Component) =
    composite.foreach(_.recount())

  def replay(composite: Component) =
    composite.foreach(_.replay())

  def deliver(composite: Component) =
    composite.foreach(_.deliver())
}