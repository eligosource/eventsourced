fork := true

libraryDependencies ++= Seq(
  "org.apache.hadoop" % "hadoop-core" % "1.1.1"  % "compile",
  "org.apache.hadoop" % "hadoop-test" % "1.1.1"  % "test",
  "org.apache.hbase"  % "hbase"       % "0.94.5" % "compile",
  "org.apache.hbase"  % "hbase"       % "0.94.5" % "test" classifier "tests",
  "org.hbase" % "asynchbase" % "1.4.1"
    exclude("org.slf4j", "log4j-over-slf4j")
    exclude("org.slf4j", "jcl-over-slf4j"),
  "org.slf4j" % "slf4j-log4j12" % "1.6.0"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
