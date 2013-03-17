package org.eligosource.eventsourced.journal.mongodb

import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.{MongodProcess, MongodExecutable, MongodStarter}
import com.mongodb.casbah.Imports._
import de.flapdoodle.embed.process.runtime.Network

package object casbah {

  val mongoDBName = "eventsourced"
  val mongoDBColl = "events"
  val mongoDBVer = Version.V2_2_3
  val mongoLocalHostName = Network.getLocalHost.getCanonicalHostName
  val mongoLocalHostIPV6 = Network.localhostIsIPv6()

  var runtime: MongodStarter = _
  var mongodExe: MongodExecutable = _
  var mongod: MongodProcess = _
  var mongoConn: MongoConnection = _
}
