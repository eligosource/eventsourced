package org.eligosource.eventsourced.journal.mongodb

import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.{MongodProcess, MongodExecutable, MongodStarter}
import de.flapdoodle.embed.process.runtime.Network

package object casbah {

  val mongoVer = Version.V2_4_3
  val mongoLocalHostName = Network.getLocalHost.getCanonicalHostName
  val mongoLocalHostIPV6 = Network.localhostIsIPv6()
  val mongoDefaultPort = 12345

  var mongoStarter: MongodStarter = _
  var mongoExe: MongodExecutable = _
  var mongod: MongodProcess = _
}
