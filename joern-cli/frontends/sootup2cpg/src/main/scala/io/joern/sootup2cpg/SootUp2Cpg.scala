package io.joern.sootup2cpg

import io.joern.sootup2cpg.passes.{AstCreationPass, DeclarationRefPass}
import io.joern.sootup2cpg.sootup.SootUpProjectLoader
import io.joern.sootup2cpg.util.Decompiler
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass, MetaDataPass, TypeNodePass}
import io.joern.x2cpg.passes.base.MethodStubCreator
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.utils.{ExternalCommand, FileUtil}
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}

/** SootUp2CPG 前端 - 使用 SootUp 框架将 Java 字节码转换为 Joern CPG。
  *
  * ==概述==
  * 本前端是 Joern 的 Java 字节码分析前端之一，使用 SootUp (Soot 的后继项目) 作为底层 字节码解析框架。相比传统的 jimple2cpg，sootup2cpg 使用更现代的 SootUp API，
  * 提供更好的性能和更清晰的架构。
  *
  * ==处理流程==
  * {{{
  *   输入 (.jar/.class/.java)
  *       │
  *       ▼
  *   prepareInput()        // 输入整理（编译 Java 源码，提取 JAR 等）
  *       │
  *       ▼
  *   SootUpProjectLoader   // 使用 SootUp 加载类和方法
  *       │
  *       ▼
  *   AstCreationPass       // 核心：将 SootUp 模型转换为 CPG AST
  *       │
  *       ▼
  *   TypeNodePass          // 创建类型节点
  *       │
  *       ▼
  *   MethodStubCreator     // 为外部方法创建存根（关键！支持数据流分析）
  *       │
  *       ▼
  *   DeclarationRefPass    // 链接标识符到声明
  *       │
  *       ▼
  *   CPG 输出
  * }}}
  *
  * ==与 jimple2cpg 的区别==
  *   - 使用 SootUp 而非 Soot Classic
  *   - CFG 生成策略：Root -> Entry (改良版，见 AstCreator 文档)
  *   - 更好的增量分析支持（未来计划）
  *
  * @see
  *   [[io.joern.sootup2cpg.astcreation.AstCreator]] CFG 生成核心逻辑
  * @see
  *   [[io.joern.sootup2cpg.sootup.SootUpProjectLoader]] SootUp 项目加载器
  */
class SootUp2Cpg extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig: Config = Config()

  private val logger = LoggerFactory.getLogger(classOf[SootUp2Cpg])

  /** 创建 CPG 的主入口方法。
    *
    * @param config
    *   前端配置（输入路径、输出路径、类路径等）
    * @return
    *   生成的 CPG，包装在 Try 中以处理可能的异常
    */
  override def createCpg(config: Config): Try[Cpg] = {
    withNewEmptyCpg(config.outputPath, config: Config) { (cpg, config) =>
      FileUtil.usingTemporaryDirectory("sootup2cpg-") { tmpDir =>
        // 步骤 1: 输入预处理
        // - 如果输入是 Java 源码且配置了编译，则编译为 .class
        // - 如果输入包含 JAR 或额外类路径，则解压/整理到临时目录
        prepareInput(config, tmpDir) match {
          case Failure(exception)     => throw exception
          case Success(preparedInput) =>
            // 步骤 2: 使用 SootUp 加载项目
            // 尝试反编译以获取源码映射 -> 这仿照 jimple2cpg 的行为
            val inputFiles =
              collectFiles(preparedInput, f => f.toString.endsWith(".class") || f.toString.endsWith(".jar"))
            println(s"[SootUp2Cpg] 正在反编译 ${inputFiles.size} 个输入文件...")
            val decompiledSource = new Decompiler(inputFiles).decompile().toMap

            val project = SootUpProjectLoader.load(preparedInput, config, decompiledSource)

            // 步骤 3: 元数据 Pass（语言标识等）
            new MetaDataPass(cpg, "JAVA", config.inputPath).createAndApply()

            // 步骤 4: AST 创建 Pass（核心！生成 AST 和 CFG）
            val astCreator = new AstCreationPass(project, cpg, config)
            println(s"[SootUp2Cpg] Created AstCreationPass with ${project.classes.size} classes.")
            astCreator.createAndApply()

            // 步骤 5: 类型节点 Pass
            TypeNodePass.withRegisteredTypes(astCreator.global.usedTypes.keys().asScala.toList, cpg).createAndApply()

            // 步骤 6: 方法存根创建 Pass
            // 重要：为 CPG 中调用但未定义的外部方法创建存根
            // 这对数据流分析至关重要，否则调用外部方法的位置会成为数据流的断点
            new MethodStubCreator(cpg).createAndApply()

            // 步骤 7: 声明引用 Pass（链接标识符到其声明）
            new DeclarationRefPass(cpg).createAndApply()

            // 步骤 8: Java 配置文件 Pass（处理 pom.xml 等）
            JavaConfigFileCreationPass(cpg, Option(preparedInput.toString), config).createAndApply()
        }
      }
    }
  }

  /** 预处理输入路径，将多种来源的类文件整合到统一的 staging 目录。
    *
    * ==处理逻辑==
    * {{{
    *   输入检测
    *       │
    *       ├─ 单个 JAR 且无额外依赖 ──> 直接返回原路径（无需 staging）
    *       │
    *       └─ 多来源输入 ──> 创建 staging 目录
    *               │
    *               ├─ 复制 .class 文件（保留包结构）
    *               ├─ 复制 JAR 文件（不解压，SootUp 可直接读取）
    *               ├─ 复制额外类路径 JAR
    *               └─ [可选] 编译 .java 源码
    * }}}
    *
    * ==设计说明==
    * '''为什么需要 staging？'''
    *   - SootUpProjectLoader 需要一个统一的路径来加载所有类。
    *   - 当输入包含 .class + JAR + 额外依赖时，需要把它们放到同一目录。
    *
    * '''JAR 是否需要解压？'''
    *   - 不需要。SootUp 可以直接从 JAR 中读取 .class 文件。
    *   - 这里只是**复制** JAR 到 staging，而非解压。
    *
    * @param config
    *   前端配置
    * @param tmpDir
    *   临时目录（用于 staging）
    * @return
    *   staging 目录路径，或原始输入路径（如果无需 staging）
    */
  private def prepareInput(config: Config, tmpDir: Path): Try[Path] = {
    // 规范化输入路径
    val input = Paths.get(config.inputPath).toAbsolutePath.normalize()
    if (!Files.exists(input)) {
      return Failure(new IllegalArgumentException(s"Input path does not exist: $input"))
    }

    val javaSources = collectFiles(input, _.toString.endsWith(".java"))
    val jarFiles    = collectFiles(input, _.toString.endsWith(".jar"))
    val classFiles  = collectFiles(input, _.toString.endsWith(".class"))

    val extraClasspathJars = config.extraClasspath.map(Paths.get(_).toAbsolutePath.normalize())
    val shouldCompile      = config.compileJavaSources && javaSources.nonEmpty
    val shouldStage        = shouldCompile || extraClasspathJars.nonEmpty || classFiles.nonEmpty

    if (!shouldStage) {
      // 无需整理：直接把输入交给 jimple2cpg（例如输入为单个 jar）。
      return Success(input)
    }

    val stagingDir = tmpDir.resolve("staging")
    Files.createDirectories(stagingDir)

    // 将字节码相关输入整理到同一目录，便于 jimple2cpg 统一加载。
    copyClassFiles(input, classFiles, stagingDir)
    copyJarFiles(jarFiles, stagingDir)
    // copyJarFiles(extraClasspathJars, stagingDir) // 修正：不要复制额外的 CP JAR，它们通过配置加载

    if (shouldCompile) {
      // 将源码编译到 staging 目录，方便混合项目“开箱即用”。
      val classpathEntries = (jarFiles ++ extraClasspathJars).distinct
      compileJavaSources(
        sources = javaSources,
        outputDir = stagingDir,
        classpathEntries = classpathEntries,
        javacOptions = config.javacOptions,
        workingDir = if (Files.isDirectory(input)) input else input.getParent
      )
    } else {
      Success(stagingDir)
    }
  }

  /** 递归收集符合条件的文件。
    *
    * 支持单文件或目录输入：
    *   - 目录：递归遍历，收集所有符合谓词的文件
    *   - 单文件：如果符合谓词则返回，否则返回空列表
    *
    * @param root
    *   起始路径（文件或目录）
    * @param predicate
    *   文件过滤条件（如 _.endsWith(".jar")）
    * @return
    *   符合条件的文件路径列表
    */
  private def collectFiles(root: Path, predicate: Path => Boolean): List[Path] = {
    if (Files.isDirectory(root)) {
      // 目录：递归遍历并过滤
      Using.resource(Files.walk(root)) { stream =>
        stream.iterator().asScala.filter(p => Files.isRegularFile(p) && predicate(p)).toList
      }
    } else if (Files.isRegularFile(root) && predicate(root)) {
      List(root)
    } else {
      Nil
    }
  }

  /** 复制 .class 文件到 staging 目录，保留包结构。
    *
    * Java 类的包结构由目录层级决定（如 com/example/Foo.class）。 复制时必须保留相对路径，否则类加载会失败。
    *
    * @param inputRoot
    *   输入根目录（用于计算相对路径）
    * @param classFiles
    *   要复制的 .class 文件列表
    * @param stagingDir
    *   目标 staging 目录
    */
  private def copyClassFiles(inputRoot: Path, classFiles: Seq[Path], stagingDir: Path): Unit = {
    classFiles.foreach { file =>
      val relative =
        if (Files.isDirectory(inputRoot)) inputRoot.relativize(file)
        else file.getFileName
      val target = stagingDir.resolve(relative)
      Files.createDirectories(target.getParent)
      Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /** 复制 JAR 文件到 staging 目录（不解压）。
    *
    * ==重要说明==
    * '''JAR 不需要解压'''：SootUp 可以直接从 JAR 内部读取 .class 文件。 这里只是复制 JAR 到 staging 目录，以便统一管理输入来源。
    *
    * @param jarFiles
    *   要复制的 JAR 文件列表
    * @param stagingDir
    *   目标 staging 目录
    */
  private def copyJarFiles(jarFiles: Seq[Path], stagingDir: Path): Unit = {
    // 去重后逐个复制
    jarFiles.distinct.foreach { jar =>
      val target = stagingDir.resolve(jar.getFileName)
      if (!Files.exists(target)) {
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  /** 编译 Java 源码为 .class 文件。
    *
    * 调用系统的 `javac` 命令将 .java 源码编译为 .class 文件， 输出到指定目录，供后续 SootUp 字节码分析使用。
    *
    * ==使用场景==
    *   - 混合项目：同时包含源码和预编译字节码
    *   - 源码分析：输入为 Java 源码而非 JAR
    *
    * @param sources
    *   要编译的 .java 源文件列表
    * @param outputDir
    *   .class 输出目录
    * @param classpathEntries
    *   编译时类路径（依赖库）
    * @param javacOptions
    *   额外的 javac 参数
    * @param workingDir
    *   工作目录（用于解析相对路径）
    * @return
    *   成功时返回输出目录，失败时返回异常
    */
  private def compileJavaSources(
    sources: Seq[Path],
    outputDir: Path,
    classpathEntries: Seq[Path],
    javacOptions: Seq[String],
    workingDir: Path
  ): Try[Path] = {
    // 如果没有源文件，直接返回成功
    if (sources.isEmpty) {
      return Success(outputDir)
    }

    val classpath =
      if (classpathEntries.nonEmpty) {
        Seq("-classpath", classpathEntries.map(_.toString).distinct.mkString(File.pathSeparator))
      } else {
        Seq.empty
      }

    val cmd =
      Seq("javac") ++ javacOptions ++ classpath ++ Seq("-d", outputDir.toString) ++ sources.map(_.toString)

    val result = ExternalCommand.run(cmd, workingDir = Option(workingDir))
    if (result.successful) {
      Success(outputDir)
    } else {
      val msg = result.stdOutAndError.mkString("\n")
      logger.warn(s"javac failed for ${sources.size} source files")
      Failure(new RuntimeException(s"javac failed:\n$msg"))
    }
  }

}
