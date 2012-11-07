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

class NodeActor(selfAddress: Address) extends Actor {
  val extension = EventsourcingExtension(context.system)

  var door: Option[ActorRef] = None
  var doorCreatedByMe = false

  def receive = {
    case state: CurrentClusterState => {
      state.leader.foreach(setupDoor)
    }
    case LeaderChanged(Some(leaderAddress)) => {
      setupDoor(leaderAddress)
    }
    case cmd: String => {
      door foreach { _ forward Message(cmd) }
    }
  }

  def setupDoor(leaderAddress: Address) {
    if (selfAddress == leaderAddress) {
      if (!doorCreatedByMe) {
        val destination = context.actorFor("akka://journal@127.0.0.1:2553/user/destination")
        extension.channelOf(DefaultChannelProps(1, destination).withName("destination"))
        extension.processorOf(Props(new Door with Emitter with Eventsourced { val id = 1 }), Some("door"))
        extension.recover()
        door = Some(extension.processors(1))
        doorCreatedByMe = true
        println("recovered door at %s" format selfAddress)
      }
    } else {
      if (doorCreatedByMe && door.isDefined) {
        context.stop(extension.processors(1))
        context.stop(extension.channels(1))
      }
      door = Some(context.actorFor(self.path.toStringWithAddress(leaderAddress) + "/door"))
      doorCreatedByMe = false
      println("referenced door at %s" format leaderAddress)
    }
  }
}

object Node {
  def main(args: Array[String]) {
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    val system = ActorSystem("node", ConfigFactory.load("cluster"))
    val journal = system.actorFor("akka://journal@127.0.0.1:2553/user/journal")

    val cluster = Cluster(system)
    val extension = EventsourcingExtension(system, journal)

    val nodeActor = system.actorOf(Props(new NodeActor(cluster.selfAddress)))
    cluster.subscribe(nodeActor, classOf[ClusterDomainEvent])

    implicit val timeout = Timeout(5 seconds)
    implicit val executor = system.dispatcher

    Thread.sleep(2000)

    while (true) {
      print("command (open|close): ")
      val cmd = Console.readLine()
      if (cmd != null) nodeActor ? cmd onSuccess { case r => println(r) }
      Thread.sleep(200)
    }
  }
}

object Journal extends App {
  implicit val system = ActorSystem("journal", ConfigFactory.load("journal"))
  val journal = system.actorOf(Props(new JournalioJournal(new File("target/cluster"))), "journal")
  val destination = system.actorOf(Props(new Destination with Receiver with Confirm), "destination")

  class Destination extends Actor {
    def receive = {
      case DoorMoved(toState, count) =>
        sender ! ("moved %d times: door now %s" format (count, toState.toString.toLowerCase))
      case DoorNotMoved(state, cmd) =>
        sender ! ("%s: door is %s" format (cmd, state.toString.toLowerCase))
      case NotSupported(cmd) =>
        sender ! ("not supported: %s" format cmd)
    }
  }
}
