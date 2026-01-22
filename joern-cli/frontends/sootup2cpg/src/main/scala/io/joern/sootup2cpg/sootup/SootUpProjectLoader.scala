package io.joern.sootup2cpg.sootup

import io.joern.sootup2cpg.Config
import io.joern.sootup2cpg.sootup.SootUpModel.*
import org.slf4j.LoggerFactory
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.{
  DefaultRuntimeAnalysisInputLocation,
  JavaClassPathAnalysisInputLocation,
  OTFCompileAnalysisInputLocation
}
import sootup.java.core.JavaSootClass
import sootup.java.core.types.JavaClassType
import sootup.java.core.views.JavaView
import sootup.interceptors.BytecodeBodyInterceptors

import java.io.File
import java.nio.file.{Files, Path}
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.collection.mutable
import scala.util.Try
import scala.util.Using

/** 使用 SootUp 加载项目并抽取结构信息。
  *
  * 说明：当前实现仅提供结构化骨架，后续会替换为 SootUp 的真实加载逻辑。
  */
object SootUpProjectLoader {
  private val logger = LoggerFactory.getLogger(getClass)

  private final case class SummaryEntry(
    classFullName: String,
    methodName: String,
    paramTypes: Option[Seq[String]],
    isStatic: Boolean
  )

  def load(input: Path, config: Config, decompiledSource: Map[String, String] = Map.empty): SootUpProject = {
    // 使用 SootUp 的 JavaView 加载类信息（支持混合源码/字节码输入）
    val javaFiles = collectFiles(input, p => p.toString.endsWith(".java"))

    val inputLocations = new java.util.ArrayList[AnalysisInputLocation]()
    // 可选加载运行时类型（JDK），默认关闭以保持与 jimple2cpg 行为一致
    if (config.loadJdkRuntime) {
      inputLocations.add(new DefaultRuntimeAnalysisInputLocation(SourceType.Library))
    }

    if (config.treatInputJarsAsLibrary) {
      // 输入路径作为应用代码：目录本身；目录内的 jar 作为库
      val (appEntries, libEntries) = buildClassPathPartitions(input)
      val appClassPath             = appEntries.mkString(File.pathSeparator)
      inputLocations.add(new JavaClassPathAnalysisInputLocation(appClassPath, SourceType.Application))
      if (libEntries.nonEmpty) {
        val libClassPath = libEntries.mkString(File.pathSeparator)
        inputLocations.add(new JavaClassPathAnalysisInputLocation(libClassPath, SourceType.Library))
      }
    } else {
      // 兼容旧行为：输入目录及目录内 jar 一并作为应用代码
      val inputClassPath = buildClassPathEntries(input).mkString(File.pathSeparator)
      inputLocations.add(new JavaClassPathAnalysisInputLocation(inputClassPath, SourceType.Application))
    }

    // 额外 classpath 作为库
    if (config.extraClasspath.nonEmpty) {
      val extraCp = config.extraClasspath.mkString(File.pathSeparator)
      inputLocations.add(new JavaClassPathAnalysisInputLocation(extraCp, SourceType.Library))
    }

    // 可选：即时编译 .java 文件（由 config 控制）
    if (config.compileJavaSources && javaFiles.nonEmpty) {
      inputLocations.add(
        new OTFCompileAnalysisInputLocation(
          javaFiles.asJava,
          SourceType.Application,
          BytecodeBodyInterceptors.Default.getBodyInterceptors
        )
      )
    }

    val applicationPackages = if (javaFiles.nonEmpty) {
      collectPackagePrefixes(javaFiles)
    } else if (Files.isDirectory(input) && !config.treatInputJarsAsLibrary) {
      // 当以仅字节码模式处理（无 .java 文件）且开启 --input-jars-as-application 时，
      // 从 .class 文件路径提取包名以将其标记为应用代码
      val classFiles = collectFiles(input, p => p.toString.endsWith(".class"))
      val packages = classFiles.flatMap { path =>
        val relativePath = input.toAbsolutePath.relativize(path.toAbsolutePath).toString
        val className    = relativePath.stripSuffix(".class").replace(File.separator, ".")
        // 提取包名（截取最后一个点之前的部分）
        className.split("\\.").dropRight(1) match {
          case parts if parts.nonEmpty => Some(parts.mkString("."))
          case _                       => None
        }
      }.distinct
      println(s"[SootUpProjectLoader] Derived ${packages.size} application packages from .class files")
      packages
    } else if (Files.isRegularFile(input) && input.toString.endsWith(".jar")) {
      // 修正：当输入为单个 JAR 文件时，从 JAR 条目中提取包名
      import java.util.jar.JarFile
      val jarFile = new JarFile(input.toFile)
      try {
        val packages = jarFile
          .entries()
          .asScala
          .filter(e => e.getName.endsWith(".class") && !e.getName.startsWith("META-INF"))
          .flatMap { entry =>
            val className = entry.getName.stripSuffix(".class").replace("/", ".")
            className.split("\\.").dropRight(1) match {
              case parts if parts.nonEmpty => Some(parts.mkString("."))
              case _                       => None
            }
          }
          .toSeq
          .distinct
        println(s"[SootUpProjectLoader] Derived ${packages.size} application packages from JAR: ${input.getFileName}")
        packages
      } finally {
        jarFile.close()
      }
    } else if (Files.isDirectory(input)) {
      // 修正：扫描 staging 目录下的所有 JAR 文件并提取包名
      import java.util.jar.JarFile
      val jarsInDir = collectFiles(input, p => p.toString.endsWith(".jar"))
      if (jarsInDir.nonEmpty) {
        val packages = jarsInDir.flatMap { jarPath =>
          val jarFile = new JarFile(jarPath.toFile)
          try {
            jarFile
              .entries()
              .asScala
              .filter(e => e.getName.endsWith(".class") && !e.getName.startsWith("META-INF"))
              .flatMap { entry =>
                val className = entry.getName.stripSuffix(".class").replace("/", ".")
                className.split("\\.").dropRight(1) match {
                  case parts if parts.nonEmpty => Some(parts.mkString("."))
                  case _                       => None
                }
              }
              .toSeq
          } finally {
            jarFile.close()
          }
        }.distinct
        println(
          s"[SootUpProjectLoader] Derived ${packages.size} application packages from ${jarsInDir.size} JARs in staging dir"
        )
        packages
      } else {
        Seq.empty
      }
    } else {
      Seq.empty
    }

    val summaryIndex = loadSummaryIndex(config)

    val view = new JavaView(inputLocations)

    // 强制加载：扫描输入中的所有 .class 文件并强制加载
    // 直接使用这些类，因为 view.getClasses() 可能不会列出 ClassPath 输入的类。
    val forcedClasses = mutable.ArrayBuffer[JavaSootClass]()

    if (Files.isDirectory(input)) {
      val classFiles = collectFiles(input, p => p.toString.endsWith(".class"))

      classFiles.foreach { path =>
        val relativePath = input.toAbsolutePath.relativize(path.toAbsolutePath).toString
        // 简单假设：文件路径与包结构匹配
        val className = relativePath
          .stripSuffix(".class")
          .replace(File.separator, ".")

        try {
          val optClass = view.getClass(view.getIdentifierFactory.getClassType(className))
          if (optClass.isPresent) {
            forcedClasses += optClass.get()
          } else {
            println(s"[SootUpProjectLoader] Failed to load class: $className (not found in view)")
          }
        } catch {
          case e: Exception =>
            println(s"[SootUpProjectLoader] Exception loading class: $className")
            e.printStackTrace()
        }
      }
    }

    // 合并视图中的类（如果有）和强制加载的类
    val viewClasses = view.getClasses.iterator().asScala.toSeq
    val allClasses  = (viewClasses ++ forcedClasses).distinct

    // 根据源码类型确定每个类的应用状态
    val classToSootUpClass = allClasses.asJava
      .parallelStream()
      .map { clazz =>
        val isApplicationByLocation = clazz.isApplicationClass
        buildClassFrom(clazz, isApplicationByLocation, applicationPackages, decompiledSource, config, summaryIndex)
      }
      .collect(Collectors.toList[SootUpClass])
      .asScala
      .toSeq

    // 修正：过滤掉库类 - 仅保留 Application 类用于 CPG 生成
    val applicationClasses = classToSootUpClass.filter(!_.isExternal)
    println(
      s"[SootUpProjectLoader] Filtered ${classToSootUpClass.size} total -> ${applicationClasses.size} application classes"
    )

    if (applicationClasses.isEmpty) {
      logger.info(s"未从 SootUp 加载到类：$input")
    }
    println(s"[SootUpProjectLoader] Final SootUpProject contains ${applicationClasses.size} classes.")
    SootUpProject(applicationClasses)
  }

  // 递归收集文件，兼容传入单文件/目录
  private def collectFiles(root: Path, predicate: Path => Boolean): Seq[Path] = {
    if (Files.isDirectory(root)) {
      Using.resource(Files.walk(root)) { stream =>
        stream.iterator().asScala.filter(p => Files.isRegularFile(p) && predicate(p)).toSeq
      }
    } else if (Files.isRegularFile(root) && predicate(root)) {
      Seq(root)
    } else {
      Seq.empty
    }
  }

  // 生成 classpath 列表：目录本身作为应用；目录内 jar 作为库
  private def buildClassPathPartitions(input: Path): (Seq[String], Seq[String]) = {
    if (Files.isDirectory(input)) {
      val jars = collectFiles(input, p => p.toString.endsWith(".jar")).map(_.toString)
      (Seq(input.toString).distinct, jars.distinct)
    } else {
      (Seq(input.toString), Seq.empty)
    }
  }

  // 生成 classpath 列表：目录本身 + 目录内 jar（旧行为）
  private def buildClassPathEntries(input: Path): Seq[String] = {
    if (Files.isDirectory(input)) {
      val jars = collectFiles(input, p => p.toString.endsWith(".jar")).map(_.toString)
      (Seq(input.toString) ++ jars).distinct
    } else {
      Seq(input.toString)
    }
  }

  private def collectPackagePrefixes(javaFiles: Seq[Path]): Seq[String] = {
    val packageRegex = "^\\s*package\\s+([\\w\\.]+)\\s*;".r
    javaFiles.flatMap { path =>
      Using.resource(scala.io.Source.fromFile(path.toFile)) { source =>
        source.getLines().collectFirst { case packageRegex(name) => name }
      }
    }.distinct
  }

  // 将 SootUp 的类模型转为内部结构化模型，便于后续 AST 构建
  private val classCounter = new AtomicInteger(0)
  private def buildClassFrom(
    clazz: JavaSootClass,
    isApplicationByLocation: Boolean,
    applicationPackages: Seq[String],
    decompiledSource: Map[String, String],
    config: Config,
    summaryIndex: Map[String, Seq[SummaryEntry]]
  ): SootUpClass = {
    val currentCount = classCounter.incrementAndGet()
    if (currentCount % 100 == 0) {
      println(s"[SootUpProjectLoader] Progress: $currentCount classes processed...")
    }
    val classType   = clazz.getType.asInstanceOf[JavaClassType]
    val packageName = classType.getPackageName.getName
    val fullName    = classType.getFullyQualifiedName
    val className   = classType.getClassName
    val sourcePath  = clazz.getClassSource.getSourcePath.toAbsolutePath.normalize().toString

    // 合并自动检测的应用包和用户通过 --include-package 强制指定的包
    val allAppPackages = applicationPackages ++ config.includePackages

    // 判断逻辑：如果类属于 Application 包（或其子包），则视为 Application 类。
    // 这决定了是否提取该类的方法体 (Source Code) 还是仅提取方法签名 (Library Code)。
    val isApplicationByPackage =
      allAppPackages.nonEmpty && allAppPackages.exists { pkg =>
        fullName == pkg || fullName.startsWith(s"$pkg.")
      }

    val sourceContent = decompiledSource.get(fullName)
    // 当应用包列表来源于 .class 文件（仅字节码模式）时，
    // 优先信任基于包名的分类，而非 SootUp 的默认分类
    val isApplicationClass = isApplicationByPackage || (!applicationPackages.nonEmpty && clazz.isApplicationClass)

    val isExternalClass = !isApplicationClass

    // 每处理 100 个类记录一次日志（主要是为了显示进度）
    if (System.identityHashCode(clazz) % 100 == 0) {
      if (isExternalClass) println(s"[SootUpProjectLoader] 处理类: $fullName (Library)")
    }

    // 继承与接口信息
    val superclass = clazz.getSuperclass.toScala.map(_.toString).toSeq
    val interfaces = clazz.getInterfaces.asScala.map(_.toString).toSeq

    // 字段抽取
    val fields = clazz.getFields.asScala.toSeq.map { field =>
      SootUpField(name = field.getName, typeFullName = field.getType.toString, isStatic = field.isStatic)
    }

    // 方法抽取（含 body 局部变量、语句与异常处理入口）
    val summaryEntries = summaryIndex.getOrElse(fullName, Seq.empty)

    val methods = clazz.getMethods.asScala.toSeq.map { method =>
      val signature  = method.getSignature
      val paramTypes = signature.getParameterTypes.asScala.toSeq
      val params = paramTypes.zipWithIndex.map { case (typ, idx) =>
        SootUpParam(name = s"p$idx", typeFullName = typ.toString, order = idx + 1)
      }
      val subSignature = signature.getSubSignature.toString
      val fullNameSig  = signature.toString

      val isSummaryMethod = summaryEntries.exists(entry => matchesSummary(method, entry))

      val isExternalMethod = isExternalClass || !method.hasBody || (config.stripBodiesForSummary && isSummaryMethod)
      val (bodyLocals, bodyStmts, exceptionHandlers, stmtGraph) =
        if (!isExternalMethod) {

          val body        = method.getBody
          val bodyStmts   = body.getStmts.asScala.toSeq
          val stmtGraph   = body.getStmtGraph
          val stmtToIndex = bodyStmts.zipWithIndex.toMap
          val handlers    = mutable.Map.empty[Int, mutable.Set[String]]
          stmtGraph.getNodes.asScala.foreach { stmt =>
            val exceptional = stmtGraph.exceptionalSuccessors(stmt).asScala
            exceptional.foreach { case (excType, handlerStmt) =>
              stmtToIndex.get(handlerStmt).foreach { idx =>
                val bucket = handlers.getOrElseUpdate(idx, mutable.Set.empty[String])
                bucket.add(excType.toString)
              }
            }
          }
          val handlerMap = handlers.view.mapValues(_.toSeq.sorted).toMap
          (body.getLocals.asScala.toSeq, bodyStmts, handlerMap, Option(stmtGraph))
        } else {
          (Seq.empty, Seq.empty, Map.empty, None)
        }

      // 提取注解信息
      val annotations =
        try {
          method
            .getAnnotations()
            .asScala
            .map { annot =>
              // AnnotationUsage API: 使用 getAnnotation() 获取 ClassType
              annot.getAnnotation().toString
            }
            .toSeq
        } catch {
          case _: Exception => Seq.empty // 如果 getAnnotations 失败，返回空列表
        }

      SootUpMethod(
        name = method.getName,
        fullName = fullNameSig,
        signature = subSignature,
        returnType = signature.getType.toString,
        parameters = params,
        isStatic = method.isStatic,
        isExternal = isExternalMethod,
        locals = bodyLocals,
        stmts = bodyStmts,
        exceptionHandlers = exceptionHandlers,
        graph = stmtGraph,
        annotations = annotations
      )
    }

    SootUpClass(
      name = className,
      fullName = fullName,
      packageName = packageName,
      filename = sourcePath,
      isExternal = isExternalClass,
      inheritsFrom = superclass ++ interfaces,
      fields = fields,
      methods = methods,
      sourceCode = sourceContent
    )
  }

  private def matchesSummary(method: sootup.java.core.JavaSootMethod, entry: SummaryEntry): Boolean = {
    val methodName = method.getName
    if (methodName != entry.methodName) return false
    if (entry.isStatic && !method.isStatic) return false
    entry.paramTypes match {
      case None => true
      case Some(expected) =>
        val actual = method.getSignature.getParameterTypes.asScala.toSeq.map(_.toString)
        actual.length == expected.length && actual.zip(expected).forall { case (a, e) => typeMatches(e, a) }
    }
  }

  private def typeMatches(expected: String, actual: String): Boolean = {
    val exp = expected.trim
    if (exp.isEmpty) return true
    if (actual == exp) return true
    val actualSimple = actual.split("\\.").lastOption.getOrElse(actual)
    actualSimple == exp || actual.endsWith(s".$exp") || actual.endsWith(s"$$$exp")
  }

  private def loadSummaryIndex(config: Config): Map[String, Seq[SummaryEntry]] = {
    if (!config.stripBodiesForSummary) return Map.empty
    val summaryDirOpt = config.summaryModelDir.map(Path.of(_))
    if (summaryDirOpt.isEmpty) {
      logger.warn("strip-bodies-for-summary is enabled but summary-model-dir is not set; skipping summary stripping")
      return Map.empty
    }
    val summaryPath = summaryDirOpt.get
    val summaryFiles =
      if (Files.isDirectory(summaryPath)) {
        collectFiles(summaryPath, p => p.toString.endsWith(".yml"))
      } else if (Files.isRegularFile(summaryPath) && summaryPath.toString.endsWith(".yml")) {
        Seq(summaryPath)
      } else {
        logger.warn(s"summary-model-dir does not exist or is not a .yml: $summaryPath")
        Seq.empty
      }
    val entries = summaryFiles.flatMap(parseSummaryEntries)
    val filtered =
      if (config.summaryPackagePrefixes.nonEmpty) {
        entries.filter(e => config.summaryPackagePrefixes.exists(p => e.classFullName.startsWith(p)))
      } else {
        entries
      }
    filtered.groupBy(_.classFullName)
  }

  private def parseSummaryEntries(path: Path): Seq[SummaryEntry] = {
    val entries        = mutable.ArrayBuffer.empty[SummaryEntry]
    var inSummaryModel = false
    var inData         = false
    val lines          = Using.resource(scala.io.Source.fromFile(path.toFile))(_.getLines().toSeq)
    lines.foreach { line =>
      val trimmed = line.trim
      if (trimmed.startsWith("extensible:")) {
        inSummaryModel = trimmed.endsWith("summaryModel")
        inData = false
      } else if (trimmed.startsWith("data:")) {
        inData = inSummaryModel
      } else if (inSummaryModel && inData && trimmed.startsWith("- [")) {
        parseSummaryRow(trimmed).foreach(entries.addOne)
      }
    }
    entries.toSeq
  }

  private def parseSummaryRow(line: String): Option[SummaryEntry] = {
    val start = line.indexOf('[')
    val end   = line.lastIndexOf(']')
    if (start < 0 || end <= start) return None
    val content = line.substring(start + 1, end)
    val parts   = splitCsvLike(content).map(unquote)
    if (parts.length < 5) return None
    val pkg        = parts(0)
    val typeName   = parts(1)
    val isStatic   = parts(2).equalsIgnoreCase("true")
    val rawMethod  = parts(3)
    val signature  = parts(4)
    val simpleType = typeName.split("\\$").lastOption.getOrElse(typeName)
    val methodName = if (rawMethod == simpleType) "<init>" else rawMethod
    val paramTypes = parseParamTypes(signature)
    Some(SummaryEntry(s"$pkg.$typeName", methodName, paramTypes, isStatic))
  }

  private def parseParamTypes(signature: String): Option[Seq[String]] = {
    val sig = signature.trim
    if (sig.isEmpty) return None
    if (!sig.startsWith("(") || !sig.endsWith(")")) return None
    val inner = sig.substring(1, sig.length - 1).trim
    if (inner.isEmpty) return Some(Seq.empty)
    val parts = inner.split(",").toSeq.map(_.trim).filter(_.nonEmpty)
    Some(parts)
  }

  private def splitCsvLike(content: String): Seq[String] = {
    val result   = mutable.ArrayBuffer.empty[String]
    val buf      = new StringBuilder
    var inQuotes = false
    var escape   = false
    content.foreach { ch =>
      if (escape) {
        buf.append(ch)
        escape = false
      } else if (ch == '\\' && inQuotes) {
        escape = true
      } else if (ch == '"') {
        inQuotes = !inQuotes
      } else if (ch == ',' && !inQuotes) {
        result.addOne(buf.toString.trim)
        buf.clear()
      } else {
        buf.append(ch)
      }
    }
    if (buf.nonEmpty) result.addOne(buf.toString.trim)
    result.toSeq
  }

  private def unquote(value: String): String = {
    val v = value.trim
    if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) {
      v.substring(1, v.length - 1)
    } else {
      v
    }
  }
}
