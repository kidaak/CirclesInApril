name := "CirclesinApril"

version := "1.0"

scalaVersion := "2.11.6"

//libraryDependencies += "se.fishtank" % "css-selectors-scala_2.9.1" % "0.1.0"


libraryDependencies += "org.jsoup" % "jsoup" % "1.7.2"

libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.24.1"

libraryDependencies += "org.apache.xmlgraphics" % "batik-svggen" % "1.7"

libraryDependencies += "org.apache.xmlgraphics" % "batik-transcoder" % "1.7"

libraryDependencies += "org.apache.xmlgraphics" % "batik-codec" % "1.7"

libraryDependencies += "org.json" % "json" % "20140107"

mainClass in Compile := Some("GeoPicassoRx")

val buildSettings = Defaults.defaultSettings ++ Seq(
  //…
  javaOptions += "-Xmx32G"
  //…
)
