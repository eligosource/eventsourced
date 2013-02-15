import com.typesafe.sbt.osgi.SbtOsgi

Nobootcp.settings

libraryDependencies ++= Seq(
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.6" % "compile"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.0,3.0.0)\"",
  "org.fusesource.leveldbjni;version=\"[1.4.1,2.0.0)\";resolution:=optional",
  "org.iq80.leveldb;version=\"[1.4.1,2.0.0)\";resolution:=optional"
)
