import com.typesafe.sbt.osgi.SbtOsgi

resolvers += "Sonatype OSS"           at "https://oss.sonatype.org/content/repositories/releases"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies ++= Seq(
  "org.mongodb"  %% "casbah-core"    %  "2.5.0"           % "compile",
  "org.mongodb"  %% "casbah-commons" %  "2.5.0"           % "compile"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\"",
  "com.mongodb.casbah;version=\"[2.5.0,3.0.0)\";resolution:=optional"
)
