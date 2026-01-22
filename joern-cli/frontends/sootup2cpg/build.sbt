name := "sootup2cpg"

dependsOn(
  Projects.x2cpg              % "compile->compile;test->test",
  Projects.linterRules % ScalafixConfig
)

libraryDependencies ++= Seq(
  "io.shiftleft"  %% "codepropertygraph" % Versions.cpg,
  "org.soot-oss"   % "sootup.core" % "2.0.0",
  "org.soot-oss"   % "sootup.java.core" % "2.0.0",
  "org.soot-oss"   % "sootup.java.bytecode.frontend" % "2.0.0",
  "org.benf"       % "cfr"         % "0.152",
  "org.scalatest" %% "scalatest"   % Versions.scalatest % Test
)

enablePlugins(JavaAppPackaging, LauncherJarPlugin)
trapExit    := false
Test / fork := true
