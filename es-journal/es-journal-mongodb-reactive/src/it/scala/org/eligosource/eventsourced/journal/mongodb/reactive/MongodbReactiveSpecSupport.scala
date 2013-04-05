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
package org.eligosource.eventsourced.journal.mongodb.reactive

import org.scalatest.{Suite, BeforeAndAfterAll}
import de.flapdoodle.embed.mongo.{MongodProcess, MongodExecutable, Command, MongodStarter}
import de.flapdoodle.embed.mongo.config.{RuntimeConfigBuilder, MongodConfig}
import de.flapdoodle.embed.process.io.{NullProcessor, Processors}
import de.flapdoodle.embed.process.config.io.ProcessOutput
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.runtime.Network

/**
* This class provides test support for starting and stopping the embedded mongo instance.
*/
trait MongodbReactiveSpecSupport extends BeforeAndAfterAll { this: Suite =>

  val mongoVer = Version.V2_4_0_RC3
  val mongoLocalHostName = Network.getLocalHost.getCanonicalHostName
  val mongoLocalHostIPV6 = Network.localhostIsIPv6()
  val mongoDefaultPort = 34567

  var mongoStarter: MongodStarter = _
  var mongoExe: MongodExecutable = _
  var mongod: MongodProcess = _

  override def beforeAll() {

    // Used to filter out console output messages.
    val processOutput = new ProcessOutput(Processors.named("[mongod>]", new NullProcessor),
      Processors.named("[MONGOD>]", new NullProcessor), Processors.named("[console>]", new NullProcessor))

    val runtimeConfig = new RuntimeConfigBuilder()
      .defaults(Command.MongoD)
      .processOutput(processOutput)
      .build()

    // Startup embedded mongodb.
    mongoStarter = MongodStarter.getInstance(runtimeConfig)
    mongoExe = mongoStarter.prepare(new MongodConfig(mongoVer, mongoDefaultPort, mongoLocalHostIPV6))
    mongod = mongoExe.start()
  }

  override def afterAll() = try {
    mongod.stop()
    mongoExe.stop()
  } catch { case _: Throwable => /* ignore */ }
}
