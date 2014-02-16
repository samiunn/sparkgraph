import AssemblyKeys._ // put this at the top of the file

name := "SparkGremlin"

version := "0.1"

scalaVersion := "2.10.0"

libraryDependencies ++= Seq(
	"org.apache.spark" %% "spark-core" % "0.9.0-incubating",
	"org.apache.spark" %% "spark-graphx" % "0.9.0-incubating",
	"com.tinkerpop.blueprints" % "blueprints-core" % "2.4.0",
	"com.tinkerpop.blueprints" % "blueprints-test" % "2.4.0" % "test",
	"com.tinkerpop" % "pipes" % "2.4.0",
	"com.tinkerpop.gremlin" % "gremlin-java" % "2.4.0",
	"com.tinkerpop.gremlin" % "gremlin-test" % "2.4.0" % "test",
	"org.scalacheck" %% "scalacheck" % "1.10.1" % "test",
    "com.novocode" % "junit-interface" % "0.9" % "test"
)	

resolvers ++= Seq(
    "Akka Repository" at "http://repo.akka.io/releases/",
    "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype Releases" at "http://oss.sonatype.org/content/repositories/releases"
)

assemblySettings

assembleArtifact in packageScala := false

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
  {
    case PathList("com", "esotericsoftware", xs @ _*) => MergeStrategy.first
    case PathList("javax", "servlet", xs @ _*) => MergeStrategy.first
    case PathList("org", "w3c", xs @ _*) => MergeStrategy.first
    case "about.html"     => MergeStrategy.discard
    case "log4j.properties"     => MergeStrategy.concat
    case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
    case x => MergeStrategy.first
  }
}

test in assembly := {}


