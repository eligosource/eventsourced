import com.typesafe.sbt.osgi.SbtOsgi.OsgiKeys

libraryDependencies in ThisBuild ++= Seq(
  "org.scalatest" %% "scalatest" % Version.ScalaTest % "test"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"   %% "akka-actor"    % Version.Akka % "compile"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
