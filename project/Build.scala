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
import sbt._
import Keys._

import com.typesafe.sbt.osgi.SbtOsgi.{ OsgiKeys, osgiSettings, defaultOsgiSettings }

object Version {
  val Scala = "2.10.1"
  val Akka = "2.1.2"
  val ScalaTest = "1.9.1"
}

object Compiler {
  val defaultSettings = Seq(
    scalacOptions in Compile ++= Seq("-target:jvm-1.6", "-unchecked", "-feature", "-language:postfixOps", "-language:implicitConversions"),
    javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6")
  )
}

object Tests {
  val defaultSettings = Seq(
    parallelExecution in Test := false
  )
}

object Publish {
  val nexus = "http://repo.eligotech.com/nexus/content/repositories/"

  val defaultSettings = Seq(
    credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
    publishMavenStyle := true,
    publishTo <<= (version) { (v: String) =>
      if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "eligosource-snapshots")
      else                             Some("releases"  at nexus + "eligosource-releases")
    }
  )

  val parentSettings = Seq(
    publishArtifact in Compile := false
  )
}

object Osgi {
  val commonExport = Seq(
    "org.eligosource*"
  )

  val defaultSettings = defaultOsgiSettings ++ Seq(
    OsgiKeys.exportPackage := commonExport,
    OsgiKeys.privatePackage := Nil
  )
}

object Nobootcp {
  import java.io.File._

  def runNobootcpInputTask(configuration: Configuration) = inputTask {
    (argTask: TaskKey[Seq[String]]) => (argTask, streams, fullClasspath in configuration) map { (at, st, cp) =>
      val runCp = cp.map(_.data).mkString(pathSeparator)
      val runOpts = Seq("-classpath", runCp) ++ at
      val result = Fork.java.fork(None, runOpts, None, Map(), true, StdoutOutput).exitValue()
      if (result != 0) sys.error("Run failed")
    }
  }

  val testNobootcpSettings = test <<= (streams, productDirectories in Test, fullClasspath in Test) map { (st, pd, cp) =>
    val testCp = cp.map(_.data).mkString(pathSeparator)
    val testExec = "org.scalatest.tools.Runner"
    val testPath = pd(0).toString
    val testOpts = Seq("-classpath", testCp, testExec, "-R", testPath, "-o")
    val result = Fork.java.fork(None, testOpts, None, Map(), false, LoggedOutput(st.log)).exitValue()
    if (result != 0) sys.error("Tests failed")
  }

  val runNobootcp = InputKey[Unit]("run-nobootcp", "Runs main classes without Scala library on the boot classpath")

  val mainRunNobootcpSettings = runNobootcp <<= runNobootcpInputTask(Runtime)
  val testRunNobootcpSettings = runNobootcp <<= runNobootcpInputTask(Test)

  lazy val settings =
    mainRunNobootcpSettings ++
    testRunNobootcpSettings ++
    testNobootcpSettings
}

object EventsourcedBuild extends Build {
  lazy val defaultSettings =
    Defaults.defaultSettings ++
    Compiler.defaultSettings ++
    Publish.defaultSettings ++
    Tests.defaultSettings ++
    Osgi.defaultSettings

  lazy val unidocExcludeSettings = Seq(
    Unidoc.unidocExclude := Seq(esCoreTest.id, esExamples.id)
  )
  lazy val unidocSettings =
    Unidoc.defaultSettings ++ unidocExcludeSettings

  lazy val es = Project(
    id = "eventsourced",
    base = file("."),
    settings = defaultSettings ++ unidocSettings ++ Publish.parentSettings
  ) aggregate(esCore, esCoreTest, esExamples, esJournal)

  lazy val esCore = Project(
    id = "eventsourced-core",
    base = file("es-core"),
    settings = defaultSettings
  )

  lazy val esCoreTest = Project(
    id = "eventsourced-core-test",
    base = file("es-core-test"),
    settings = defaultSettings
  ) dependsOn(esCore,
    esJournalLeveldb % "test->test;compile->compile",
    esJournalHbase % "test->it;compile->compile",
    esJournalMongodbCasbah % "test->test;compile->compile",
    esJournalMongodbReactive % "test->it;compile->compile"
  )

  lazy val esExamples = Project(
    id = "eventsourced-examples",
    base = file("es-examples"),
    settings = defaultSettings
  ) dependsOn(esCore, esCoreTest % "compile->test", esJournalInmem, esJournalLeveldb, esJournalJournalio, esJournalHbase)

  lazy val esJournal = Project(
    id = "eventsourced-journal",
    base = file("es-journal"),
    settings = defaultSettings ++ Publish.parentSettings
  ) aggregate(esJournalCommon, esJournalInmem, esJournalHbase, esJournalLeveldb, esJournalJournalio, esJournalMongodbCasbah, esJournalMongodbReactive, esJournalDynamodb)

  lazy val esJournalCommon = Project(
    id = "eventsourced-journal-common",
    base = file("es-journal/es-journal-common"),
    settings = defaultSettings
  ) dependsOn(esCore)

  lazy val esJournalInmem = Project(
    id = "eventsourced-journal-inmem",
    base = file("es-journal/es-journal-inmem"),
    settings = defaultSettings
  ) dependsOn(esJournalCommon % "test->test;compile->compile")

  lazy val esJournalHbase = Project(
    id = "eventsourced-journal-hbase",
    base = file("es-journal/es-journal-hbase"),
    settings = defaultSettings ++ Defaults.itSettings
  ) dependsOn(esJournalCommon % "it->test;compile->compile") configs( IntegrationTest )

  lazy val esJournalLeveldb = Project(
    id = "eventsourced-journal-leveldb",
    base = file("es-journal/es-journal-leveldb"),
    settings = defaultSettings
  ) dependsOn(esJournalCommon % "test->test;compile->compile")

  lazy val esJournalJournalio = Project(
    id = "eventsourced-journal-journalio",
    base = file("es-journal/es-journal-journalio"),
    settings = defaultSettings
  ) dependsOn(esJournalCommon % "test->test;compile->compile")

  lazy val esJournalMongodbCasbah = Project(
    id = "eventsourced-journal-mongodb-casbah",
    base = file("es-journal/es-journal-mongodb-casbah"),
    settings = defaultSettings
  ) dependsOn(esJournalCommon % "test->test;compile->compile")

  lazy val esJournalMongodbReactive = Project(
    id = "eventsourced-journal-mongodb-reactive",
    base = file("es-journal/es-journal-mongodb-reactive"),
    settings = defaultSettings ++ Defaults.itSettings
  ) dependsOn(esJournalCommon % "it->test;compile->compile") configs( IntegrationTest )

  lazy val esJournalDynamodb = Project(
    id = "eventsourced-journal-dynamodb",
    base = file("es-journal/es-journal-dynamodb"),
    settings = defaultSettings ++ Defaults.itSettings
  ) dependsOn(esJournalCommon % "it->test;compile->compile") configs( IntegrationTest )

}
