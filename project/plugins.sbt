resolvers += "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"

resolvers += Classpaths.typesafeResolver

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.1.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.5.0-SNAPSHOT")
