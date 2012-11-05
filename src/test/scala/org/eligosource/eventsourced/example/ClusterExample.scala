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

/* --------------------------------------------------------------------------
 * Actor state failover via event sourcing in Akka cluster (stateful actor
 * is the event-sourced CountingEchoActor, a cluster-wide singleton).
 *
 * How to run in sbt console:
 *
 * // start journal (not part of cluster)
 * test:run-main org.eligosource.eventsourced.example.Journal
 *
 * // start seed node 1
 * test:run-main org.eligosource.eventsourced.example.Peer 2561
 *
 * // start seed node 2
 * test:run-main org.eligosource.eventsourced.example.Peer 2562
 *
 * // start any other node
 * test:run-main org.eligosource.eventsourced.example.Peer
 *
 * Then make some calls when prompted, for example on the last node started.
 * Echoes are written to stdout. After some calls, kill seed node 1 (leader)
 * and wait for some other node becoming the leader and recovering the echo
 * actor (takes 1-2 seconds). Then make further calls and observe the
 * recovered echo actor state.
 * -------------------------------------------------------------------------- */

/**
 * Event sourced actor that echoes `Call`s. The `Echo` contains the call message and
 * a counter value which is the state of the actor. The counter is increased with each
 * call.
 */
class CountingEchoActor extends Actor {
  import CountingEchoActor._

  var counter = 0

  def receive = {
    case Call(msg) => {
      counter += 1
      sender ! Echo("%d: %s" format (counter, msg))
    }
  }
}

object CountingEchoActor {
  case class Call(msg: String)
  case class Echo(msg: String)
}

/**
 * Actor that maintains a cluster-wide `CountingEchoActor` singleton. If it is the
 * leader it creates and recovers the `CountingEchoActor` singleton, otherwise it
 * obtains a remote reference to the singleton. Handles `Call`s by forwarding them
 * to the `CountingEchoActor`.
 */
class PeerActor(selfAddress: Address) extends Actor {
  import CountingEchoActor._

  val extension = EventsourcingExtension(context.system)

  var countingEcho: Option[ActorRef] = None
  var countingEchoCreatedByMe = false

  def receive = {
    case Call(msg) => countingEcho foreach { _ forward Message(Call(msg)) }
    case state: CurrentClusterState =>
      state.leader.foreach(setupCountingEcho)
    case LeaderChanged(Some(leaderAddress)) => {
      setupCountingEcho(leaderAddress)
    }
  }

  def setupCountingEcho(leaderAddress: Address) {
    if (selfAddress == leaderAddress) {
      if (!countingEchoCreatedByMe) {
        countingEcho = Some(extension.processorOf(Props(new CountingEchoActor with Receiver with Eventsourced { val id = 1 }), Some("echo")))
        countingEchoCreatedByMe = true
        extension.recover()
        println("recovered counting echo service at %s" format selfAddress)
      }
    } else {
      if (countingEchoCreatedByMe) countingEcho foreach { context.stop(_) }
      countingEcho = Some(context.actorFor(self.path.toStringWithAddress(leaderAddress) + "/echo"))
      countingEchoCreatedByMe = false
      println("referenced counting echo service at %s" format leaderAddress)
    }
  }
}

/**
 * Application that starts a `PeerActor` and joins the cluster. It prompts for calls
 * and prints echoes.
 */
object Peer {
  import CountingEchoActor._

  def main(args: Array[String]) {
    if (args.nonEmpty) System.setProperty("akka.remote.netty.port", args(0))

    val system = ActorSystem("experimental", ConfigFactory.load("cluster"))
    val journal = system.actorFor("akka://journal@127.0.0.1:2553/user/journal")

    val cluster = Cluster(system)
    val extension = EventsourcingExtension(system, journal)

    val peerActor = system.actorOf(Props(new PeerActor(cluster.selfAddress)))
    cluster.subscribe(peerActor, classOf[ClusterDomainEvent])

    implicit val timeout = Timeout(5 seconds)
    implicit val executor = system.dispatcher

    Thread.sleep(2000)

    while (true) {
      print("call: ")
      val line = Console.readLine()
      if (line != null) {
        peerActor ? Call(line) onSuccess {
          case Echo(msg) => println("echo: %s" format msg)
        }
      }
      Thread.sleep(200)
    }
  }
}

/**
 * Application that starts a remote journal that is used by the cluster.
 * This is a SPOF but will be replaced with a HA journal soon ...
 */
object Journal extends App {
  implicit val system = ActorSystem("journal", ConfigFactory.load("journal"))
  val journal = system.actorOf(Props(new JournalioJournal(new File("target/cluster"))), "journal")
}
