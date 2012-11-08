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
package org.eligosource.eventsourced.example

import java.io.File

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.ask
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.util.Timeout

import com.typesafe.config.ConfigFactory

import org.eligosource.eventsourced.core._
import org.eligosource.eventsourced.journal._

import FsmExample._

/**
 * Manages a cluster-wide `Door` singleton. `Door` is a finite state machine (FSM)
 * taken from `FsmExample`. `NodeActor` listens to cluster events. If it becomes
 * the leader it creates the `Door` instance, otherwise, it obtains a remote `Door`
 * reference from the other leader. Door commands ("open" or "close") received by
 * `NodeActor` are forwarded as event messages to the door singleton.
 *
 * If the JVM of the leader crashes, another `NodeActor` will be determined to be
 * the new leader. The new leader re-creates the `Door` instance and recovers its
 * state by replaying journaled event messages. All other `NodeActor`s update their
 * remote references.
 *
 * @param selfAddress address of the cluster node this actor is running on.
 */
class NodeActor(selfAddress: Address) extends Actor {
  val extension = EventsourcingExtension(context.system)

  var door: Option[ActorRef] = None
  var doorCreatedByMe = false

  def receive = {
    case state: CurrentClusterState =>
      state.leader.foreach(setupDoor)
    case LeaderChanged(leader) =>
      leader.foreach(setupDoor)
    case cmd: String =>
      door foreach { _ forward Message(cmd) }
  }

  def setupDoor(leaderAddress: Address) {
    if (selfAddress == leaderAddress) {
      // this actor is the leader
      if (!doorCreatedByMe) {
        // Create a new event-sourced Door FSM and recover its state. The "destination"
        // channel is used by the Door FSM to emit events to a remote destination actor.
        extension.channelOf(DefaultChannelProps(1, destination).withName("destination"))
        extension.processorOf(Props(new Door with Emitter with Eventsourced { val id = 1 }), Some("door"))
        extension.recover()
        // Store reference to Door singleton locally
        door = Some(extension.processors(1))
        doorCreatedByMe = true
        println("MASTER: recovered door at %s" format selfAddress)
      }
    } else {
      // another actor is the leader
      if (doorCreatedByMe && door.isDefined) {
        // stop and deregister Door on this node
        context.stop(extension.processors(1))
        // stop and deregister destination channel on this node
        context.stop(extension.channels(1))
      }
      // obtain remote Door reference from leader and store that reference locally
      door = Some(context.actorFor(self.path.toStringWithAddress(leaderAddress) + "/door"))
      doorCreatedByMe = false
      println("SLAVE: referenced door at %s" format leaderAddress)
    }
  }

  /** Obtain remote destination reference */
  def destination = context.actorFor("akka://journal@127.0.0.1:2553/user/destination")
}

/**
 * Application that joins the cluster by subscribing a `NodeActor` to it. It also
 * prompts users to enter door commands ("open" or "close") and sends them to the
 * created `NodeActor`. Users can enter commands on any cluster node. Application
 * argument is an optional port number (needed for seed nodes).
 */
object Node {
  def main(args: Array[String]) {
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    // create an actor system with a configuration loaded from "cluster.conf"
    val system = ActorSystem("node", ConfigFactory.load("cluster"))

    // obtain remote journal reference
    val journal = system.actorFor("akka://journal@127.0.0.1:2553/user/journal")

    // initialize cluster extension
    val cluster = Cluster(system)

    // initialize event-sourcing extension
    EventsourcingExtension(system, journal)

    // create an actor that manages the cluster-wide Door singleton
    val nodeActor = system.actorOf(Props(new NodeActor(cluster.selfAddress)))

    // subscribe that actor to the cluster so that it receives cluster events
    cluster.subscribe(nodeActor, classOf[ClusterDomainEvent])

    implicit val timeout = Timeout(5 seconds)
    implicit val executor = system.dispatcher

    Thread.sleep(2000)

    // prompt user for "open" and "close" commands on stdin and write responses
    // to stdout
    while (true) {
      print("command (open|close): ")
      val cmd = Console.readLine()
      if (cmd != null) nodeActor ? cmd onSuccess { case r => println(r) }
      Thread.sleep(200)
    }
  }
}

/**
 * Application that starts remote `journal` and `destination` actors which
 * are used by the cluster. This application is a standalone application
 * that is remotely accessed but not part of the cluster.
 */
object Journal extends App {
  // create an actor system with a configruation loaded from "cluster.conf"
  implicit val system = ActorSystem("journal", ConfigFactory.load("journal"))

  // create a journal and register that journal actor actor under name "journal"
  // in the underlying actor system (needed for remote lookup).
  val journal = JournalioJournal(new File("target/cluster"), Some("journal"))

  // create a destination and register that destination actor under name "destination"
  // in the underlying actor system (needed for remote lookup).
  val destination = system.actorOf(Props(new Destination with Receiver with Confirm), "destination")

  /**
   * Receives event messages from the `Door` FSM and responds to the initial sender
   * (which is a future created by `Node`). Responses are written to stdout by the
   * `Node` application.
   */
  class Destination extends Actor {
    def receive = {
      case DoorMoved(state, count) =>
        sender ! ("moved %d times: door now %s" format (count, state.toString.toLowerCase))
      case DoorNotMoved(state, cmd) =>
        sender ! ("%s: door is %s" format (cmd, state.toString.toLowerCase))
      case NotSupported(cmd) =>
        sender ! ("not supported: %s" format cmd)
    }
  }
}
