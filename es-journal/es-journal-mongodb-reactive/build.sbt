import com.typesafe.sbt.osgi.SbtOsgi

resolvers +=  "Eligosource Releases Repo"  at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases"

resolvers +=  "Eligosource Snapshots Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-snapshots"

resolvers += "Sonatype OSS"           at "https://oss.sonatype.org/content/repositories/releases"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

resolvers += "Typesafe"               at "http://repo.typesafe.com/typesafe/releases"

libraryDependencies ++= Seq(
  "org.reactivemongo"   %% "reactivemongo"              % "0.9-AKKA-2.2.0-RC1-SNAPSHOT"    % "compile,it"
      exclude("ch.qos.logback", "logback-core")
      exclude("ch.qos.logback", "logback-classic"),
  "de.flapdoodle.embed"  % "de.flapdoodle.embed.mongo"  % "1.33"                           % "it",
  "org.slf4j"            % "slf4j-log4j12"              % "1.6.0",
  "org.scalatest"       %% "scalatest"                  % Version.ScalaTest % "it"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
