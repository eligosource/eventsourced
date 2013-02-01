organization := "org.eligosource"

name := "eventsourced"

version := "0.5-SNAPSHOT"

scalaVersion := "2.10.0"

resolvers += "Journalio Repo" at "http://repo.eligotech.com/nexus/content/repositories/eligosource-releases"

libraryDependencies ++= Seq(
  "com.google.protobuf"       %  "protobuf-java"             % "2.4.1"  % "compile",
  "com.typesafe.akka"         %% "akka-actor"                % "2.1.0"  % "compile",
  "commons-io"                %  "commons-io"                % "2.3"    % "compile",
  "journalio"                 %  "journalio"                 % "1.2"    % "compile",
  "org.fusesource.leveldbjni" %  "leveldbjni-all"            % "1.4.1"  % "compile",
  "com.typesafe.akka"         %% "akka-cluster-experimental" % "2.1.0"  % "test",
  "org.scala-lang"            %  "scala-actors"              % "2.10.0" % "test",
  "org.scalatest"             %% "scalatest"                 % "1.9.1"  % "test"
)
