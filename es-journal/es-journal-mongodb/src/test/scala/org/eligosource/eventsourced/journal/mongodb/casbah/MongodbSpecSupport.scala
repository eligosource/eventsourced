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
package org.eligosource.eventsourced.journal.mongodb.casbah

import org.scalatest.{Suite, BeforeAndAfterAll}
import com.mongodb.casbah.MongoConnection
import de.flapdoodle.embed.mongo.{Command, MongodStarter}
import de.flapdoodle.embed.mongo.config.{RuntimeConfigBuilder, MongodConfig}
import de.flapdoodle.embed.process.io.{NullProcessor, Processors}
import de.flapdoodle.embed.process.config.io.ProcessOutput

/**
 * This class provides test support for starting and stopping the embedded mongo instance.
 */
trait MongodbSpecSupport extends BeforeAndAfterAll { this: Suite =>

  def mongoDBConnPort = 14445

  override def beforeAll() {

    // Used to filter out console output messages.
    val processOutput = new ProcessOutput(Processors.named("[mongod>]", new NullProcessor),
      Processors.named("[MONGOD>]", new NullProcessor), Processors.named("[console>]", new NullProcessor))

    val runtimeConfig = new RuntimeConfigBuilder()
      .defaults(Command.MongoD)
      .processOutput(processOutput)
      .build()

    runtime = MongodStarter.getInstance(runtimeConfig)

    mongodExe = runtime.prepare(new MongodConfig(mongoDBVer, mongoDBConnPort, mongoLocalHostIPV6))
    mongod = mongodExe.start()
    mongoConn = MongoConnection(mongoLocalHostName, mongoDBConnPort)
  }

  override def afterAll() {
    mongod.stop()
    mongodExe.stop()
  }
}
