import sbt._
import Keys._
import com.typesafe.sbt.osgi.SbtOsgi.{ OsgiKeys, osgiSettings, defaultOsgiSettings }

object Settings {
  val buildOrganization = "org.eligosource"
  val buildVersion      = "0.5-SNAPSHOT"
  val buildScalaVersion = Version.Scala
  val buildSettings = Defaults.defaultSettings ++ Seq(
    organization := buildOrganization,
    version      := buildVersion,
    scalaVersion := buildScalaVersion,
    scalaBinaryVersion <<= scalaVersion.identity
  )

  import Resolvers._

  val defaultSettings = buildSettings ++ Seq(
    resolvers ++= Seq(journalioRepo),
    scalacOptions ++= Seq("-unchecked"),
    parallelExecution in Test := false
  )
}

object Resolvers {
  val journalioRepo = "Journalio Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases"
}

object Dependencies {
  import Dependency._

  val core = Seq(akkaActor, akkaCluster, commonsIo, journalIo, levelDbJni, protobuf, scalaTest, scalaActors)
}

object Version {
  val Scala = "2.10.0-RC2"
  val Akka  = "2.1.0-RC2"
}

object Dependency {
  import Version._

  // -----------------------------------------------
  //  Compile
  // -----------------------------------------------

  val protobuf    = "com.google.protobuf"       %  "protobuf-java"  % "2.4.1" % "compile"
  val akkaActor   = "com.typesafe.akka"         %% "akka-actor"     % Akka    % "compile"
  val commonsIo   = "commons-io"                %  "commons-io"     % "2.3"   % "compile"
  val journalIo   = "journalio"                 %  "journalio"      % "1.2"   % "compile"
  val levelDbJni  = "org.fusesource.leveldbjni" %  "leveldbjni-all" % "1.4.1" % "compile"

  // -----------------------------------------------
  //  Test
  // -----------------------------------------------

  val akkaCluster = "com.typesafe.akka" %% "akka-cluster-experimental" % Akka  % "test"
  val scalaActors = "org.scala-lang"    %  "scala-actors"              % Scala % "test"
  val scalaTest   = "org.scalatest"     %% "scalatest"                 % "1.8" % "test"
}

object Publish {
  val nexus = "http://repo.eligotech.com/nexus/content/repositories/"
  val publishSettings = Seq(
    credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
    publishMavenStyle := true,
    publishTo <<= (version) { (v: String) =>
        if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "eligosource-snapshots")
        else                             Some("releases"  at nexus + "eligosource-releases")
    }
  )
}

object EventsourcedBuild extends Build {
  import java.io.File._
  import Publish._
  import Settings._

  lazy val eventsourced = Project(
    id = "eventsourced",
    base = file("."),
    settings = defaultSettings ++ publishSettings ++ Seq(
      libraryDependencies ++= Dependencies.core,
      mainRunNobootcpSetting,
      testRunNobootcpSetting,
      testNobootcpSetting
    ) ++ osgiSettings ++ Seq(
      OsgiKeys.importPackage := Seq(
        "akka*;version=\"[2.1.0,3.0.0)\"",
        "scala*;version=\"[2.10.0,2.11.0)\"",
        "journal.io.api;version=\"[1.2,2.0)\";resolution:=optional",
        "org.fusesource.leveldbjni;version=\"[1.4.1,2.0.0)\";resolution:=optional",
        "org.iq80.leveldb;version=\"[1.4.1,2.0.0)\";resolution:=optional",
        "*"
      ),
      OsgiKeys.exportPackage := Seq(
        "org.eligosource*"
      ),
      OsgiKeys.privatePackage := Nil
    )
  )

  val runNobootcp =
    InputKey[Unit]("run-nobootcp", "Runs main classes without Scala library on the boot classpath")

  val mainRunNobootcpSetting = runNobootcp <<= runNobootcpInputTask(Runtime)
  val testRunNobootcpSetting = runNobootcp <<= runNobootcpInputTask(Test)

  def runNobootcpInputTask(configuration: Configuration) = inputTask {
    (argTask: TaskKey[Seq[String]]) => (argTask, streams, fullClasspath in configuration) map { (at, st, cp) =>
      val runCp = cp.map(_.data).mkString(pathSeparator)
      val runOpts = Seq("-classpath", runCp) ++ at
      val result = Fork.java.fork(None, runOpts, None, Map(), true, StdoutOutput).exitValue()
      if (result != 0) sys.error("Run failed")
    }
  }

  val testNobootcpSetting = test <<= (scalaVersion, streams, fullClasspath in Test) map { (sv, st, cp) =>
    val testCp = cp.map(_.data).mkString(pathSeparator)
    val testExec = "org.scalatest.tools.Runner"
    val testPath = "target/scala-%s/test-classes" format sv
    val testOpts = Seq("-classpath", testCp, testExec, "-R", testPath, "-o")
    val result = Fork.java.fork(None, testOpts, None, Map(), false, LoggedOutput(st.log)).exitValue()
    if (result != 0) sys.error("Tests failed")
  }
}
