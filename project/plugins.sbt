resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"

resolvers += "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.4.0")

addSbtPlugin("com.orrsella" % "sbt-sublime" % "1.0.5")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.4.0")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.1.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.6.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.0")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")
