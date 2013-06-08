import com.typesafe.sbt.osgi.SbtOsgi

resolvers += "Sonatype OSS"           at "https://oss.sonatype.org/content/repositories/releases"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

resolvers += "Typesafe"               at "http://repo.typesafe.com/typesafe/releases"


libraryDependencies ++= Seq(
  "org.mongodb"         %%  "casbah-core"                %  "2.5.1" 	 % "compile",
  "org.mongodb"         %%  "casbah-commons"             %  "2.5.1" 	 % "compile",
  "org.json4s" 		%%  "json4s-native" 		 %  "3.2.4"	 % "compile",
  "com.novus"           %   "salat-core"                 %  "2.10"       % "compile"   from "https://oss.sonatype.org/content/repositories/snapshots/com/novus/salat-core_2.10/1.9.2-SNAPSHOT/salat-core_2.10-1.9.2-SNAPSHOT.jar",
  "com.novus"           %   "salat-util"                 %  "2.10"       % "compile"   from "https://oss.sonatype.org/content/repositories/snapshots/com/novus/salat-util_2.10/1.9.2-SNAPSHOT/salat-util_2.10-1.9.2-SNAPSHOT.jar",
  "de.flapdoodle.embed" %   "de.flapdoodle.embed.mongo"  %  "1.29"  	 % "compile"
)

OsgiKeys.importPackage := Seq(
  "scala*;version=\"[2.10.0,2.11.0)\"",
  "akka*;version=\"[2.1.1,2.2.0)\""
)
