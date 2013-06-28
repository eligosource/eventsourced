import com.typesafe.sbt.osgi.SbtOsgi

resolvers += "Sonatype OSS"           at "https://oss.sonatype.org/content/repositories/releases"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

resolvers += "Typesafe"               at "http://repo.typesafe.com/typesafe/releases"

libraryDependencies ++= Seq(
  "org.mongodb"         %% "casbah-core"               %  "2.6.2" % "compile",
  "org.mongodb"         %% "casbah-commons"            %  "2.6.2" % "compile",
  "de.flapdoodle.embed"  % "de.flapdoodle.embed.mongo" %  "1.33"  % "test"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
