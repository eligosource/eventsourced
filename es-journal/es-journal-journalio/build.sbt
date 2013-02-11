import com.typesafe.sbt.osgi.SbtOsgi

resolvers += "Journalio Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases"

libraryDependencies ++= Seq(
  "journalio" % "journalio" % "1.2" % "compile"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.0,3.0.0)\"",
  "journal.io.api;version=\"[1.2,2.0)\";resolution:=optional"
)
