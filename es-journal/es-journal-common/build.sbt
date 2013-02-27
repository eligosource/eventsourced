libraryDependencies ++= Seq(
  "commons-io" % "commons-io" % "2.3" % "test"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
