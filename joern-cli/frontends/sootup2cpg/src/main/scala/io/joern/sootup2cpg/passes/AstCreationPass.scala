package io.joern.sootup2cpg.passes

import io.joern.sootup2cpg.Config
import io.joern.sootup2cpg.astcreation.AstCreator
import io.joern.sootup2cpg.sootup.SootUpModel.*
import io.joern.x2cpg.datastructures.Global
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.ForkJoinParallelCpgPass
import org.slf4j.LoggerFactory

/** AST 创建 Pass - 并行处理 SootUp 类模型，生成 CPG AST。
  *
  * ==概述==
  * 本 Pass 是 sootup2cpg 的核心数据处理单元。它继承自 `ForkJoinParallelCpgPass`， 利用 Fork-Join 并行框架高效处理大型项目中的大量类。
  *
  * ==处理流程==
  * {{{
  *   SootUpProject (输入)
  *       │
  *       ▼
  *   generateParts()  // 将 project.classes 拆分为独立任务
  *       │
  *       ▼ (并行执行)
  *   runOnPart(class) // 对每个类调用 AstCreator
  *       │
  *       ▼
  *   DiffGraphBuilder (合并到 CPG)
  * }}}
  *
  * ==并行安全==
  * 每个类的 AST 生成是独立的，通过 `AstCreator` 产生独立的 `DiffGraphBuilder`， 然后由框架安全地合并到主 CPG。
  *
  * @param project
  *   SootUp 加载的项目模型（包含类、方法等）
  * @param cpg
  *   目标 CPG（AST 将被写入此处）
  * @param config
  *   前端配置
  *
  * @see
  *   [[io.joern.sootup2cpg.astcreation.AstCreator]] 单类 AST 生成器
  */
class AstCreationPass(project: SootUpProject, cpg: Cpg, config: Config)
    extends ForkJoinParallelCpgPass[SootUpClass](cpg) {

  /** 全局状态：类型注册表，所有 AstCreator 共享 */
  val global: Global = new Global()
  private val logger = LoggerFactory.getLogger(classOf[AstCreationPass])

  /** 生成并行任务的工作单元。
    *
    * 将项目中的所有类转换为独立的处理单元，每个类可以被并行处理。
    *
    * @return
    *   类对象数组，每个元素将触发一次 runOnPart 调用
    */
  override def generateParts(): Array[? <: AnyRef] = {

    project.classes.toArray
  }

  /** 处理单个类，生成其 AST 并合并到 CPG。
    *
    * 此方法由 Fork-Join 框架并行调用，每次处理一个类：
    *   1. 创建该类专属的 AstCreator 实例 2. 调用 createAst() 生成 DiffGraphBuilder 3. 将结果合并到主 builder
    *
    * ==线程安全==
    *   - 每个类有独立的 AstCreator 和 localDiff
    *   - global（类型注册表）使用线程安全的 ConcurrentHashMap
    *   - builder.absorb() 由框架保证线程安全
    *
    * @param builder
    *   主 DiffGraphBuilder（由框架管理）
    * @param part
    *   待处理的 SootUp 类模型
    */
  override def runOnPart(builder: DiffGraphBuilder, part: SootUpClass): Unit = {
    // 调试日志：用于追踪特定类的处理

    try {
      // 为此类创建独立的 AstCreator，生成其 AST
      val localDiff = new AstCreator(part, global)(config.schemaValidation).createAst()

      builder.absorb(localDiff)
    } catch {
      case e: Exception =>
        logger.warn(s"AST 生成失败: ${part.fullName}", e)
    }
  }
}
