import com.typesafe.sbt.osgi.SbtOsgi.OsgiKeys

libraryDependencies in ThisBuild ++= Seq(
  "org.scalatest" %% "scalatest" % Version.ScalaTest % "test"
)

libraryDependencies ++= Seq(
  "org.spark-project.akka"   %% "akka-actor"    % Version.Akka % "compile",
  "commons-collections" % "commons-collections"  % "3.2.1" % "compile"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
