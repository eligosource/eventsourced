libraryDependencies ++= Seq(
  "com.google.protobuf"  %  "protobuf-java" % "2.4.1"         % "compile",
  "org.apache.hadoop"    %  "hadoop-core"   % Version.Hadoop  % "compile"
    exclude("commons-httpclient", "commons-httpclient")
    exclude("commons-beanutils", "commons-beanutils-core")
    exclude("commons-collections", "commons-collections")
    exclude("org.mortbay.jetty", "jsp-api-2.1")
    exclude("org.mortbay.jetty", "jsp-2.1")
    exclude("org.mortbay.jetty", "jetty-util")
    exclude("org.mortbay.jetty", "jetty")
    exclude("tomcat", "jasper-runtime")
    exclude("tomcat", "jasper-compiler")
    exclude("junit", "junit"),
  "com.typesafe.akka"   %% "akka-remote"    % Version.Akka    % "test",
  "commons-io"           % "commons-io"     % "2.3"           % "test"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\"",
  "com.google.protobuf*;version=\"[2.4.0,2.5.0)\""
)
