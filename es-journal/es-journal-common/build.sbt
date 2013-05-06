libraryDependencies ++= Seq(
  "com.google.protobuf"  %  "protobuf-java" % "2.4.1"       % "compile",
  "com.typesafe.akka"   %% "akka-remote"    % Version.Akka  % "test",
  "commons-io"           % "commons-io"     % "2.3"         % "test"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\"",
  "com.google.protobuf*;version=\"[2.4.0,2.5.0)\""
)
