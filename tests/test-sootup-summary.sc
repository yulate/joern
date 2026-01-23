@main def exec() = {
  import java.nio.file.{Files, Paths}
  import scala.jdk.CollectionConverters._

  try {
    val testsDir   = Paths.get("tests").toAbsolutePath
    val codeDir    = testsDir.resolve("code/sootup_summary_test")
    val srcDir     = codeDir.resolve("src")
    val summaryDir = codeDir.resolve("summary")
    val outDir     = testsDir.resolve("out")
    Files.createDirectories(outDir)
    val cpgFile = outDir.resolve("sootup_summary_test.bin").toString

    println(s"Generating CPG to $cpgFile...")

    // Use the staged binary
    val binPath = Paths.get("joern-cli/frontends/sootup2cpg/target/universal/stage/bin/sootup2cpg").toAbsolutePath
    if (!Files.exists(binPath)) {
      throw new RuntimeException(
        s"SootUp2CPG binary not found at $binPath. Please run: sbt \"project sootup2cpg\" stage"
      )
    }

    import scala.sys.process._

    val binDir = codeDir.resolve("bin")
    Files.createDirectories(binDir)

    // Compile Source
    println(s"Compiling source to $binDir...")
    val javacCmd = Seq(
      "javac",
      "-d",
      binDir.toString,
      // "-source", "1.8", "-target", "1.8", // Safe default, or use current. Since we are in newer environment, default is fine.
      srcDir.resolve("Test.java").toString
    )
    val javacExit = Process(javacCmd).!
    if (javacExit != 0) {
      throw new RuntimeException("Compilation failed")
    }

    val cmd = Seq(
      binPath.toString,
      "--strip-bodies-for-summary",
      "--summary-model-dir",
      summaryDir.toString,
      "--output",
      cpgFile,
      binDir.toString // Point to BIN directory
    )

    println(s"Executing: ${cmd.mkString(" ")}")
    val exitCode = Process(cmd).!
    if (exitCode != 0) {
      throw new RuntimeException(s"SootUp2CPG failed with exit code $exitCode")
    }

    println(s"Loading CPG from: $cpgFile")
    importCpg(cpgFile)

    val sourceFullName = "<org.example.Source: java.lang.String src()>"
    val sinkFullName   = "<org.example.Sink: void sink(java.lang.Object)>"

    println(s"Looking for Source: $sourceFullName")
    val source = cpg.call.methodFullNameExact(sourceFullName).l
    println(s"Source count: ${source.size}")

    println(s"Looking for Sink: $sinkFullName")
    val sink = cpg.call.methodFullNameExact(sinkFullName).argument.l
    println(s"Sink count: ${sink.size}")

    if (source.isEmpty || sink.isEmpty) {
      throw new RuntimeException("ERROR: Source or Sink not found in CPG.")
    }

    println("Verifying Taint Flows...")
    val flows = sink.reachableByFlows(source).l
    println(s"Total Flows Found: ${flows.size}")

    flows.foreach { f =>
      println(
        s"Flow: ${f.elements.map(e => e.lineNumber.map(_.toString).getOrElse("?") + ": " + e.code).mkString(" -> ")}"
      )
    }

    if (flows.size >= 6) {
      println("SUCCESS_VERIFIED: Found expected flows.")
    } else {
      throw new RuntimeException(s"FAILURE_VERIFIED: Expected at least 6 flows, found ${flows.size}")
    }

  } catch {
    case e: Exception =>
      println("ERROR during verification: " + e.getMessage)
      e.printStackTrace()
      System.exit(1)
  }
}
