package io.joern.sootup2cpg

import io.joern.sootup2cpg.Frontend.*
import io.joern.x2cpg.{SingleThreadedFrontend, X2CpgConfig, X2CpgMain}
import scopt.OParser

/** Command line configuration parameters
  */
final case class Config(
  android: Option[String] = None,
  dynamicDirs: Seq[String] = Seq.empty,
  dynamicPkgs: Seq[String] = Seq.empty,
  fullResolver: Boolean = false,
  recurse: Boolean = false,
  depth: Int = 1,
  compileJavaSources: Boolean = false,
  loadJdkRuntime: Boolean = false,
  treatInputJarsAsLibrary: Boolean = true,
  stripBodiesForSummary: Boolean = false,
  summaryModelDir: Option[String] = None,
  summaryPackagePrefixes: Seq[String] = Seq.empty,
  extraClasspath: Seq[String] = Seq.empty,
  javacOptions: Seq[String] = Seq.empty,
  includePackages: Seq[String] = Seq.empty,
  override val genericConfig: X2CpgConfig.GenericConfig = X2CpgConfig.GenericConfig()
) extends X2CpgConfig[Config] {
  override def withGenericConfig(value: X2CpgConfig.GenericConfig): Config = copy(genericConfig = value)

  // 配置辅助方法保持简洁明确，便于 CLI 行为可追踪。
  def withAndroid(android: String): Config = copy(android = Some(android))
  def withDynamicDirs(value: Seq[String]): Config = copy(dynamicDirs = value)
  def withDynamicPkgs(value: Seq[String]): Config = copy(dynamicPkgs = value)
  def withFullResolver(value: Boolean): Config = copy(fullResolver = value)
  def withRecurse(value: Boolean): Config = copy(recurse = value)
  def withDepth(value: Int): Config = copy(depth = value)
  def withCompileJavaSources(value: Boolean): Config = copy(compileJavaSources = value)
  def withLoadJdkRuntime(value: Boolean): Config = copy(loadJdkRuntime = value)
  def withTreatInputJarsAsLibrary(value: Boolean): Config = copy(treatInputJarsAsLibrary = value)
  def withStripBodiesForSummary(value: Boolean): Config = copy(stripBodiesForSummary = value)
  def withSummaryModelDir(value: String): Config = copy(summaryModelDir = Some(value))
  def withSummaryPackagePrefixes(value: Seq[String]): Config = copy(summaryPackagePrefixes = value)
  def withExtraClasspath(value: Seq[String]): Config = copy(extraClasspath = value)
  def withJavacOptions(value: Seq[String]): Config = copy(javacOptions = value)
  /** 
    * 配置显式包含的包名列表。
    * 这些包下的类将被强制视为 Application 类，提取完整的方法体（AST + CFG）。
    * 这对于需要追踪穿越框架代码（默认为 Library，无方法体）的数据流非常关键。
    */
  def withIncludePackages(value: Seq[String]): Config = copy(includePackages = value)
}

private object Frontend {

  implicit val defaultConfig: Config = Config()

  val cmdLineParser: OParser[Unit, Config] = {
    val builder = OParser.builder[Config]
    import builder.*
    OParser.sequence(
      programName("sootup2cpg"),
      opt[String]("android")
        .text("Optional path to android.jar while processing apk file.")
        .action((android, config) => config.withAndroid(android)),
      opt[Unit]("full-resolver")
        .text("enables full transitive resolution of all references found in all classes that are resolved")
        .action((_, config) => config.withFullResolver(true)),
      opt[Unit]("recurse")
        .text("recursively unpack jars")
        .action((_, config) => config.withRecurse(true)),
      opt[Int]("depth")
        .text("maximum depth to recursively unpack jars, default value 1")
        .action((depth, config) => config.withDepth(depth))
        .validate(x => if (x > 0) success else failure("depth must be greater than 0")),
      opt[Seq[String]]("dynamic-dirs")
        .valueName("<dir1>,<dir2>,...")
        .text(
          "Mark all class files in dirs as classes that may be loaded dynamically. Comma separated values for multiple directories."
        )
        .action((dynamicDirs, config) => config.withDynamicDirs(dynamicDirs)),
      opt[Seq[String]]("dynamic-pkgs")
        .valueName("<pkg1>,<pkg2>,...")
        .text(
          "Marks all class files belonging to the package pkg or any of its subpackages as classes which the application may load dynamically. Comma separated values for multiple packages."
        )
        .action((dynamicPkgs, config) => config.withDynamicPkgs(dynamicPkgs)),
      opt[Unit]("no-compile-java")
        .text("do not compile Java sources when .java files are present")
        .action((_, config) => config.withCompileJavaSources(false)),
      opt[Unit]("compile-java")
        .text("compile Java sources when .java files are present")
        .action((_, config) => config.withCompileJavaSources(true)),
      opt[Unit]("with-jdk-runtime")
        .text("include JDK runtime classes in analysis (default: disabled to match jimple2cpg)")
        .action((_, config) => config.withLoadJdkRuntime(true)),
      opt[Unit]("input-jars-as-library")
        .text("treat input directory jars as library code (default: enabled)")
        .action((_, config) => config.withTreatInputJarsAsLibrary(true)),
      opt[Unit]("input-jars-as-application")
        .text("treat input directory jars as application code (disables library classification)")
        .action((_, config) => config.withTreatInputJarsAsLibrary(false)),
      opt[Unit]("strip-bodies-for-summary")
        .text("strip method bodies when a summaryModel entry matches (forces summary usage)")
        .action((_, config) => config.withStripBodiesForSummary(true)),
      opt[String]("summary-model-dir")
        .valueName("<dir-or-file>")
        .text("path to summary model directory (or a single .yml) used for body stripping")
        .action((path, config) => config.withSummaryModelDir(path)),
      opt[Seq[String]]("summary-packages")
        .valueName("<pkg1>,<pkg2>,...")
        .text("limit summary body stripping to package prefixes")
        .action((pkgs, config) => config.withSummaryPackagePrefixes(pkgs)),
      opt[Seq[String]]("extra-classpath")
        .valueName("<jar1>,<jar2>,...")
        .text("extra classpath entries used for javac when compiling Java sources")
        .action((paths, config) => config.withExtraClasspath(paths)),
      opt[Seq[String]]("javac-options")
        .valueName("<opt1>,<opt2>,...")
        .text("extra javac options, comma separated")
        .action((opts, config) => config.withJavacOptions(opts)),
      opt[Seq[String]]("include-package")
        .valueName("<pkg1>,<pkg2>,...")
        .text("显式包含这些库包作为 Application 代码（提取AST和方法体）。这对于解决框架代码导致的数据流断裂至关重要。")
        .action((pkgs, config) => config.withIncludePackages(pkgs))
    )
  }
}

/** Entry point for command line CPG creator
  */
object Main extends X2CpgMain(new SootUp2Cpg(), cmdLineParser) with SingleThreadedFrontend
