name := "CirclesinApril"

version := "1.0"

scalaVersion := "2.11.6"

//libraryDependencies += "se.fishtank" % "css-selectors-scala_2.9.1" % "0.1.0"


libraryDependencies += "org.jsoup" % "jsoup" % "1.7.2"

libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.24.1"

mainClass in Compile := Some("GeoPicassoRx")

    