package org.eligosource.eventsourced.journal.mongodb.casbah.salat

import org.scalatest.{ Suite, BeforeAndAfterAll }
import _root_.de.flapdoodle.embed.mongo.{ MongodStarter, Command }
import _root_.de.flapdoodle.embed.mongo.config.{ MongodConfig, RuntimeConfigBuilder }
import _root_.de.flapdoodle.embed.process.io.{ NullProcessor, Processors }
import _root_.de.flapdoodle.embed.process.config.io.ProcessOutput
import org.eligosource.eventsourced.journal.mongodb.casbah.salat._

/**
 * This class provides test support for starting and stopping the embedded mongo instance.
 */
trait MongodbSpecSupport extends BeforeAndAfterAll { this: Suite =>

  def mongoPort = mongoDefaultPort

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
    mongoExe = mongoStarter.prepare(new MongodConfig(mongoVer, mongoPort, mongoLocalHostIPV6))
    mongod = mongoExe.start()
  }

  override def afterAll() {
    mongod.stop()
    mongoExe.stop()
  }
}

