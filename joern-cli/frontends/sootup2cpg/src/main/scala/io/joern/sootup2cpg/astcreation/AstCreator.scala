package io.joern.sootup2cpg.astcreation

import io.joern.sootup2cpg.sootup.SootUpModel.*
import scala.reflect.Selectable.reflectiveSelectable
import io.joern.x2cpg.Ast
import io.joern.x2cpg.Ast.storeInDiffGraph
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.{AstCreatorBase, Defines, ValidationMode}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{
  ControlStructureTypes,
  DiffGraphBuilder,
  DispatchTypes,
  EdgeTypes,
  EvaluationStrategies,
  NodeTypes,
  Operators
}
import sootup.core.jimple.basic.Value
import sootup.core.jimple.common.constant.{ClassConstant, Constant, NullConstant}
import sootup.core.jimple.common.expr.*
import sootup.core.jimple.common.ref.*
import sootup.core.jimple.common.stmt.{
  JAssignStmt,
  JGotoStmt,
  JIdentityStmt,
  JIfStmt,
  JInvokeStmt,
  JNopStmt,
  JReturnStmt,
  JReturnVoidStmt,
  JThrowStmt,
  Stmt
}
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt
import sootup.core.jimple.javabytecode.stmt.{JEnterMonitorStmt, JExitMonitorStmt}
import sootup.core.model.Position
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional

/** SootUp 到 CPG 的 AST 转换器。
  *
  * 本类是 sootup2cpg 前端的核心组件，负责将 SootUp (Jimple IR) 的类模型转换为 Joern CPG 的 AST 表示。
  *
  * ==架构概述==
  * {{{
  *   SootUpClass (输入)
  *       │
  *       ▼
  *   AstCreator.createAst()
  *       │
  *       ├── astForCompilationUnit()  // 顶层：命名空间 + 类型声明
  *       │       │
  *       │       ├── namespaceBlockForPackage()
  *       │       └── astForTypeDecl()
  *       │               │
  *       │               ├── astForField()     // 字段 -> MEMBER
  *       │               └── astForMethod()    // 方法处理
  *       │                       │
  *       │                       ├── doAstForMethod()  // 方法体构建
  *       │                       │       │
  *       │                       │       ├── astForStmt()      // 语句 -> AST
  *       │                       │       ├── linkInternalCfg() // 语句内 CFG
  *       │                       │       └── StmtGraph 遍历     // 语句间 CFG
  *       │                       │
  *       │                       └── methodAst()  // 组装 METHOD 节点
  *       │
  *       ▼
  *   DiffGraphBuilder (输出)
  * }}}
  *
  * ==CFG 生成策略==
  * CFG 生成分为两部分：
  *
  * '''1. 语句间 CFG (Inter-statement)'''
  *   - 使用 SootUp 的 `StmtGraph` 获取语句间的控制流关系。
  *   - 连接策略：`Root(Stmt A) -> Entry(Stmt B)`
  *     - Root: 语句的 AST 根节点（如 Call 节点），代表语句执行的"出口"。
  *     - Entry: 语句的第一个可执行节点（如第一个参数），代表语句执行的"入口"。
  *   - 这种策略参考了 jimple2cpg，但针对 SootUp 的 AST 结构进行了改良。
  *
  * '''2. 语句内 CFG (Intra-statement)'''
  *   - 由 `linkInternalCfg()` 处理，确保表达式求值顺序正确。
  *   - 例如：`foo(a, b)` 会生成 `a -> b -> foo` 的 CFG 边。
  *
  * ==支持的语句类型==
  *   - JAssignStmt: 赋值（包括调用返回值赋值）
  *   - JInvokeStmt: 无返回值调用
  *   - JReturnStmt / JReturnVoidStmt: 返回
  *   - JIfStmt / JGotoStmt / JSwitchStmt: 控制流
  *   - JThrowStmt: 异常抛出
  *   - JIdentityStmt: 参数/this 绑定
  *   - JEnterMonitorStmt / JExitMonitorStmt: 同步块
  *
  * @param clazz
  *   待转换的 SootUp 类模型
  * @param global
  *   全局状态（类型注册表）
  */
class AstCreator(clazz: SootUpClass, global: Global)(implicit withSchemaValidation: ValidationMode)
    extends AstCreatorBase[SootUpNode, AstCreator](clazz.filename) {

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[AstCreator])

  override def createAst(): DiffGraphBuilder = {
    // 以类为粒度构建 AST，并写入 diffGraph
    val astRoot = astForCompilationUnit(clazz)
    storeInDiffGraph(astRoot, diffGraph)
    val fileNode = NewFile()
      .name(clazz.filename)
      .content(clazz.sourceCode.getOrElse(""))
      .order(0)
    diffGraph.addNode(fileNode)
    diffGraph
  }

  protected def line(node: SootUpNode): Option[Int]      = node.lineNumber
  protected def column(node: SootUpNode): Option[Int]    = node.columnNumber
  protected def lineEnd(node: SootUpNode): Option[Int]   = node.lineNumberEnd
  protected def columnEnd(node: SootUpNode): Option[Int] = node.columnNumberEnd
  protected def code(node: SootUpNode): String           = node.code

  private def astForCompilationUnit(clazz: SootUpClass): Ast = {
    // 单文件下仅包含一个类型声明
    val namespaceBlock = namespaceBlockForPackage(clazz)
    val typeDeclAst    = astForTypeDecl(clazz, namespaceBlock.fullName)
    Ast(namespaceBlock).withChild(typeDeclAst)
  }

  private def namespaceBlockForPackage(clazz: SootUpClass): NewNamespaceBlock = {
    if (clazz.packageName.nonEmpty) {
      NewNamespaceBlock()
        .name(clazz.packageName.split("\\.").lastOption.getOrElse(clazz.packageName))
        .fullName(clazz.packageName)
        .filename(clazz.filename)
    } else {
      globalNamespaceBlock()
    }
  }

  private def astForTypeDecl(clazz: SootUpClass, namespaceFullName: String): Ast = {
    // 类型声明节点 + 成员/方法
    registerType(clazz.fullName)
    val typeDecl =
      typeDeclNode(
        clazz,
        clazz.name,
        clazz.fullName,
        clazz.filename,
        code = clazz.name,
        astParentType = NodeTypes.NAMESPACE_BLOCK,
        astParentFullName = namespaceFullName,
        inherits = clazz.inheritsFrom
      )

    val fieldAsts  = clazz.fields.map(astForField)
    val methodAsts = clazz.methods.map(astForMethod(_, clazz))
    Ast(typeDecl).withChildren(fieldAsts).withChildren(methodAsts)
  }

  private def astForField(field: SootUpField): Ast = {
    registerType(field.typeFullName)
    val member = memberNode(field, field.name, field.code, field.typeFullName)
    Ast(member)
  }

  private def astForMethod(method: SootUpMethod, clazz: SootUpClass): Ast = {
    try {
      doAstForMethod(method, clazz)
    } catch {
      case e: Throwable =>
        logger.warn(s"FAILED processing method: ${method.fullName}", e)
        e.printStackTrace()
        throw e
    }
  }

  private def doAstForMethod(method: SootUpMethod, clazz: SootUpClass): Ast = {

    registerType(method.returnType)
    method.parameters.foreach(p => registerType(p.typeFullName))

    val methodNode_ = methodNode(
      method,
      method.name,
      method.code,
      method.fullName,
      signature = Option(method.signature),
      fileName = clazz.filename,
      astParentType = Option(NodeTypes.TYPE_DECL),
      astParentFullName = Option(clazz.fullName)
    )
    if (method.isExternal) {
      methodNode_.isExternal(true)
    }

    val parameterAsts = method.parameters.map { param =>
      val paramNode = parameterInNode(
        param,
        param.name,
        param.code,
        param.order,
        isVariadic = false,
        evaluationStrategy = EvaluationStrategies.BY_SHARING,
        typeFullName = param.typeFullName
      )
      Ast(paramNode)
    }
    val thisParamAst =
      if (!method.isStatic) {
        // 与 Java 前端对齐：非静态方法补充 this 参数
        val thisType = clazz.fullName
        val thisNode = parameterInNode(
          SootUpSimpleNode("this"),
          name = "this",
          code = "this",
          index = 0,
          isVariadic = false,
          evaluationStrategy = EvaluationStrategies.BY_SHARING,
          typeFullName = Option(thisType)
        )
        Some(Ast(thisNode))
      } else {
        None
      }

    val methodReturn = methodReturnNode(method, method.returnType)

    // 生成注解 AST 节点：遍历方法的注解列表，创建 NewAnnotation 节点
    // 这些节点将被作为子节点附加到 METHOD 节点上，使查询支持 .annotation
    val annotationAsts = method.annotations.map { annotationFullName =>
      val annotationNode = NewAnnotation()
        .code(s"@${annotationFullName.split("\\.").lastOption.getOrElse(annotationFullName)}")
        .name(annotationFullName.split("\\.").lastOption.getOrElse(annotationFullName))
        .fullName(annotationFullName)
      Ast(annotationNode)
    }

    val body =
      if (method.isExternal) {
        if (method.summary.nonEmpty) {

          createSyntheticBody(method, clazz)
        } else {
          // 外部方法仅保留空的 BLOCK，避免语义被内部实现干扰
          blockAst(NewBlock().typeFullName(Defines.Any), List.empty)
        }
      } else {

        val locals = method.locals.map { local =>
          registerType(local.getType.toString)
          val localNode_ =
            localNode(SootUpSimpleNode(local.getName), local.getName, local.getName, local.getType.toString)
          local.getName -> localNode_
        }.toMap

        val paramNameByIndex = method.parameters.map(p => (p.order - 1) -> p.name).toMap

        // =============================================================================
        // 第一阶段：AST 生成与节点映射
        // =============================================================================
        //
        // 目标：遍历方法体中的所有 Jimple 语句，为每条语句生成 AST，并记录两个关键映射：
        //   - stmtToEntryNode: Stmt -> 该语句 AST 的"入口"节点（第一个可执行子节点）
        //   - stmtToRootNode:  Stmt -> 该语句 AST 的"根"节点（语句本身，如 Call 节点）
        //
        // 这两个映射在第二阶段用于正确连接语句间的 CFG 边。
        //
        val stmtToEntryNode = scala.collection.mutable.Map.empty[sootup.core.jimple.common.stmt.Stmt, NewNode]
        val stmtToRootNode  = scala.collection.mutable.Map.empty[sootup.core.jimple.common.stmt.Stmt, NewNode]

        val stmtAsts = method.stmts.zipWithIndex.flatMap { case (stmt, idx) =>
          // 将 Jimple 语句转换为 CPG AST
          val asts = astForStmt(stmt, locals, paramNameByIndex, method.exceptionHandlers, idx)

          if (asts.nonEmpty) {
            val root = asts.head.root.getOrElse(null.asInstanceOf[NewNode])
            if (root != null) {
              // Root Node (出口): 语句执行完成后的节点，通常是 Call/Assign 节点
              // 例如：对于 `x = foo(a, b)`，Root 是 `<operator>.assignment` 节点
              stmtToRootNode.put(stmt, root)

              // Entry Node (入口): 语句开始执行时的第一个节点
              // 例如：对于 `x = foo(a, b)`，Entry 是参数 `a` 的 Identifier 节点
              // 由 findEntryNode() 递归查找 AST 中最左边的可执行节点
              val entry = findEntryNode(asts.head).getOrElse(root)
              stmtToEntryNode.put(stmt, entry)

              // 建立语句内部的 CFG 边（Intra-statement CFG）
              // 确保参数按求值顺序链接：a -> b -> foo -> assignment
              linkInternalCfg(asts.head)
            }
          }
          asts
        }
        val localsAsts = locals.values.toSeq.map(Ast(_))

        // =============================================================================
        // 第二阶段：CFG 边生成
        // =============================================================================
        //
        // 使用 SootUp 的 StmtGraph 获取语句间的控制流信息，然后添加 CFG 边。
        //
        // 关键设计决策 (参考 jimple2cpg 并改良):
        // ────────────────────────────────────────────────────────────────────────────
        // │ 连接策略：Root(Stmt A) ───CFG───> Entry(Stmt B)                           │
        // │                                                                          │
        // │   Stmt A: x = foo(a)           Stmt B: y = bar(b)                        │
        // │                                                                          │
        // │       a ─CFG→ foo ─CFG→ =      b ─CFG→ bar ─CFG→ =                        │
        // │       ↑                 │      ↑                                         │
        // │     Entry             Root   Entry                                       │
        // │                         │      ↑                                         │
        // │                         └──────┘ (Inter-statement CFG)                   │
        // ────────────────────────────────────────────────────────────────────────────
        //
        // 为什么不能用 Entry -> Entry？
        //   - 如果连接 Entry(A) -> Entry(B)，会跳过 Stmt A 的执行（Root 成为死端）。
        //   - 数据流分析会丢失 Stmt A 的副作用和返回值。
        //
        method.graph.foreach { rawGraph =>
          // 使用结构类型避免导入问题（SootUp 的类型系统较复杂）
          type StmtGraphLike = Any {
            def successors(
              stmt: sootup.core.jimple.common.stmt.Stmt
            ): java.util.List[sootup.core.jimple.common.stmt.Stmt]
            def exceptionalSuccessors(
              stmt: sootup.core.jimple.common.stmt.Stmt
            ): java.util.Map[sootup.core.types.Type, sootup.core.jimple.common.stmt.Stmt]
            def getStartingStmt: sootup.core.jimple.common.stmt.Stmt
            def getTails: java.util.List[sootup.core.jimple.common.stmt.Stmt]
            def getNodes: java.util.Collection[sootup.core.jimple.common.stmt.Stmt]
          }
          val graph = rawGraph.asInstanceOf[StmtGraphLike]
          val nodes = graph.getNodes.asScala

          if (nodes.nonEmpty) {
            // 2.1 语句间 CFG 边
            // 对每条语句，将其 Root (出口) 连接到所有后继语句的 Entry (入口)
            nodes.foreach { srcStmt =>
              stmtToRootNode.get(srcStmt).foreach { srcNode =>
                // 2.1.1 正常控制流边
                graph.successors(srcStmt).asScala.foreach { dstStmt =>
                  stmtToEntryNode.get(dstStmt).foreach { dstNode =>
                    diffGraph.addEdge(srcNode, dstNode, EdgeTypes.CFG)
                  }
                }
                // 2.1.2 异常控制流边 (Exception edges)
                graph.exceptionalSuccessors(srcStmt).asScala.foreach { case (_, handlerStmt) =>
                  stmtToEntryNode.get(handlerStmt).foreach { dstNode =>
                    diffGraph.addEdge(srcNode, dstNode, EdgeTypes.CFG)
                  }
                }
              }
            }

            // 2.2 方法入口边：METHOD -> 第一条语句的 Entry
            val headStmt = graph.getStartingStmt
            stmtToEntryNode.get(headStmt).foreach { headNode =>
              diffGraph.addEdge(methodNode_, headNode, EdgeTypes.CFG)
            }

            // 2.3 方法出口边：最后一条语句的 Root -> METHOD_RETURN
            // 注意：尾语句是那些没有后继的语句（如 return, throw, 方法末尾）
            graph.getTails.asScala.foreach { tailStmt =>
              stmtToRootNode.get(tailStmt).foreach { tailNode =>
                diffGraph.addEdge(tailNode, methodReturn, EdgeTypes.CFG)
              }
            }
          }
        }

        blockAst(NewBlock().typeFullName(Defines.Any), (localsAsts ++ stmtAsts).toList)
      }
    val paramsWithThis = thisParamAst.toSeq ++ parameterAsts

    methodAst(methodNode_, paramsWithThis, body, methodReturn).withChildren(annotationAsts)
  }

  /** 注册类型并返回，方便调用链中直接使用 */
  private def registerType(typeName: String): String = {
    global.usedTypes.put(typeName, true)
    typeName
  }

  // 将 Jimple 语句转换为 AST（控制结构与表达式均在此入口）
  private def astForStmt(
    stmt: Stmt,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String],
    exceptionHandlers: Map[Int, Seq[String]],
    stmtIdx: Int
  ): Seq[Ast] = {
    val node = stmtNode(stmt)
    stmt match {
      case invokeStmt: JInvokeStmt =>
        invokeStmt.getInvokeExpr.toScala.toSeq.map { invokeExpr =>
          astForInvokeExpr(invokeExpr, node, locals, paramNameByIndex)
        }

      case assignStmt: JAssignStmt =>
        val leftAst  = astForValue(assignStmt.getLeftOp, node, locals, paramNameByIndex)
        val rightAst = astForValue(assignStmt.getRightOp, node, locals, paramNameByIndex)
        val assignCall =
          operatorCallNode(node, assignStmt.toString, Operators.assignment, Some(assignStmt.getLeftOp.getType.toString))
        Seq(callAst(assignCall, List(leftAst, rightAst)))

      case identityStmt: JIdentityStmt =>
        val leftAst  = astForValue(identityStmt.getLeftOp, node, locals, paramNameByIndex)
        val rightAst = astForValue(identityStmt.getRightOp, node, locals, paramNameByIndex)
        val assignCall =
          operatorCallNode(
            node,
            identityStmt.toString,
            Operators.assignment,
            Some(identityStmt.getLeftOp.getType.toString)
          )
        val assignAst = callAst(assignCall, List(leftAst, rightAst))
        identityStmt.getRightOp match {
          case _: JCaughtExceptionRef =>
            // 异常处理入口：用 CATCH 控制结构包裹
            exceptionHandlers.get(stmtIdx) match {
              case Some(types) if types.nonEmpty =>
                val typeRefs = types.map(t => Ast(typeRefNode(node, t, t)))
                val catchNode =
                  controlStructureNode(node, ControlStructureTypes.CATCH, s"catch (${types.mkString("|")})")
                val catchAst = controlStructureAst(catchNode, typeRefs.headOption)
                Seq(catchAst.withChildren(typeRefs.drop(1) :+ assignAst))
              case other =>
                Seq(assignAst)
            }
          case _ =>
            Seq(assignAst)
        }

      case retStmt: JReturnStmt =>
        val returnNode_ = returnNode(node, retStmt.toString)
        val valueAst    = astForValue(retStmt.getOp, node, locals, paramNameByIndex)
        Seq(returnAst(returnNode_, Seq(valueAst)))

      case _: JReturnVoidStmt =>
        Seq(returnAst(returnNode(node, "return")))

      case throwStmt: JThrowStmt =>
        val opAst = astForValue(throwStmt.getOp, node, locals, paramNameByIndex)
        val throwCall =
          callNode(
            node,
            throwStmt.toString,
            "<operator>.throw",
            "<operator>.throw",
            DispatchTypes.STATIC_DISPATCH,
            signature = Option(""),
            typeFullName = Option(throwStmt.getOp.getType.toString)
          )
        Seq(callAst(throwCall, Seq(opAst)))

      case ifStmt: JIfStmt =>
        val conditionAst = astForValue(ifStmt.getCondition, node, locals, paramNameByIndex)
        val ifNode       = controlStructureNode(node, ControlStructureTypes.IF, ifStmt.toString)
        Seq(controlStructureAst(ifNode, Option(conditionAst)))

      case switchStmt: JSwitchStmt =>
        val keyAst    = astForValue(switchStmt.getKey, node, locals, paramNameByIndex)
        val switchAst = controlStructureNode(node, ControlStructureTypes.SWITCH, switchStmt.toString)
        val caseAsts = switchStmt.getValues.asScala.map { value =>
          Ast(
            NewJumpTarget()
              .name(s"case ${value.getValue}")
              .code(s"case ${value.getValue}:")
              .lineNumber(line(node))
              .columnNumber(column(node))
          )
        }.toSeq
        val defaultAst = Ast(
          NewJumpTarget()
            .name("default")
            .code("default:")
            .lineNumber(line(node))
            .columnNumber(column(node))
        )
        Seq(controlStructureAst(switchAst, Option(keyAst)).withChildren(caseAsts :+ defaultAst))

      case enterStmt: JEnterMonitorStmt =>
        val opAst = astForValue(enterStmt.getOp, node, locals, paramNameByIndex)
        Seq(Ast(unknownNode(node, enterStmt.toString)).withChildren(Seq(opAst)))

      case exitStmt: JExitMonitorStmt =>
        val opAst = astForValue(exitStmt.getOp, node, locals, paramNameByIndex)
        Seq(Ast(unknownNode(node, exitStmt.toString)).withChildren(Seq(opAst)))

      case _: JNopStmt =>
        Seq(Ast(unknownNode(node, "nop")))

      case gotoStmt: JGotoStmt =>
        Seq(Ast(unknownNode(node, gotoStmt.toString)))

      case _ =>
        Seq(Ast(unknownNode(node, stmt.toString)))
    }
  }

  // 调用表达式（静态/动态派发）映射到 CALL
  private def astForInvokeExpr(
    invokeExpr: AbstractInvokeExpr,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val methodSignature = invokeExpr.getMethodSignature
    val methodFullName  = methodSignature.toString
    val signature       = methodSignature.getSubSignature.toString
    val name            = methodSignature.getName
    val returnType      = methodSignature.getType.toString
    val dispatchType = invokeExpr match {
      case _: JVirtualInvokeExpr | _: JInterfaceInvokeExpr =>
        DispatchTypes.DYNAMIC_DISPATCH
      case _ =>
        DispatchTypes.STATIC_DISPATCH
    }

    val callRoot =
      callNode(node, invokeExpr.toString, name, methodFullName, dispatchType, Option(signature), Option(returnType))

    val args = invokeExpr.getArgs.asScala.map(arg => astForValue(arg, node, locals, paramNameByIndex)).toSeq
    val base = invokeExpr match {
      case instance: AbstractInstanceInvokeExpr =>
        Option(astForValue(instance.getBase, node, locals, paramNameByIndex))
      case _ =>
        None
    }
    callAst(callRoot, args, base = base)
  }

  // 所有 Value 入口：本地变量、常量、引用、表达式
  private def astForValue(
    value: Value,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    value match {
      case local: sootup.core.jimple.basic.Local =>
        val identifier = identifierNode(node, local.getName, local.getName, local.getType.toString)
        locals.get(local.getName).foreach { localNode_ =>
          diffGraph.addEdge(identifier, localNode_, EdgeTypes.REF)
        }
        Ast(identifier)
      case constant: Constant =>
        constant match {
          case classConstant: ClassConstant =>
            Ast(literalNode(node, s"${classConstantCode(classConstant)}.class", classConstant.getType.toString))
          case _: NullConstant =>
            Ast(literalNode(node, "null", "null"))
          case _ =>
            Ast(literalNode(node, constant.toString, constant.getType.toString))
        }
      case arrayRef: JArrayRef =>
        astForArrayRef(arrayRef, node, locals, paramNameByIndex)
      case fieldRef: JInstanceFieldRef =>
        astForInstanceFieldRef(fieldRef, node, locals, paramNameByIndex)
      case fieldRef: JStaticFieldRef =>
        astForStaticFieldRef(fieldRef, node)
      case thisRef: JThisRef =>
        Ast(identifierNode(node, "this", "this", thisRef.getType.toString))
      case paramRef: JParameterRef =>
        val name = paramNameByIndex.getOrElse(paramRef.getIndex, s"p${paramRef.getIndex}")
        Ast(identifierNode(node, name, name, paramRef.getType.toString))
      case caughtRef: JCaughtExceptionRef =>
        Ast(identifierNode(node, caughtRef.toString, caughtRef.toString, caughtRef.getType.toString))
      case invokeExpr: AbstractInvokeExpr =>
        astForInvokeExpr(invokeExpr, node, locals, paramNameByIndex)
      case binopExpr: AbstractBinopExpr =>
        astForBinaryExpr(binopExpr, node, locals, paramNameByIndex)
      case castExpr: JCastExpr =>
        astForCastExpr(castExpr, node, locals, paramNameByIndex)
      case instanceOfExpr: JInstanceOfExpr =>
        astForInstanceOfExpr(instanceOfExpr, node, locals, paramNameByIndex)
      case lengthExpr: JLengthExpr =>
        astForUnaryExpr(Operators.lengthOf, lengthExpr, lengthExpr.getOp, node, locals, paramNameByIndex)
      case negExpr: JNegExpr =>
        astForUnaryExpr(Operators.minus, negExpr, negExpr.getOp, node, locals, paramNameByIndex)
      case newExpr: JNewExpr =>
        astForNewExpr(newExpr, node)
      case newArrayExpr: JNewArrayExpr =>
        astForNewArrayExpr(newArrayExpr, node, locals, paramNameByIndex)
      case newMultiArrayExpr: JNewMultiArrayExpr =>
        astForNewMultiArrayExpr(newMultiArrayExpr, node, locals, paramNameByIndex)
      case _ =>
        Ast(identifierNode(node, value.toString, value.toString, value.getType.toString))
    }
  }

  // 二元表达式映射为操作符 CALL
  private def astForBinaryExpr(
    expr: AbstractBinopExpr,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val operatorName = expr match {
      case _: JAddExpr  => Operators.addition
      case _: JSubExpr  => Operators.subtraction
      case _: JMulExpr  => Operators.multiplication
      case _: JDivExpr  => Operators.division
      case _: JRemExpr  => Operators.modulo
      case _: JGeExpr   => Operators.greaterEqualsThan
      case _: JGtExpr   => Operators.greaterThan
      case _: JLeExpr   => Operators.lessEqualsThan
      case _: JLtExpr   => Operators.lessThan
      case _: JShlExpr  => Operators.shiftLeft
      case _: JShrExpr  => Operators.logicalShiftRight
      case _: JUshrExpr => Operators.arithmeticShiftRight
      case _: JCmpExpr  => Operators.compare
      case _: JCmpgExpr => Operators.compare
      case _: JCmplExpr => Operators.compare
      case _: JAndExpr  => Operators.and
      case _: JOrExpr   => Operators.or
      case _: JXorExpr  => Operators.xor
      case _: JEqExpr   => Operators.equals
      case _: JNeExpr   => Operators.notEquals
      case _            => "<operator>.unknown"
    }

    val call = callNode(
      node,
      expr.toString,
      operatorName,
      operatorName,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(expr.getType.toString)
    )
    val args = Seq(
      astForValue(expr.getOp1, node, locals, paramNameByIndex),
      astForValue(expr.getOp2, node, locals, paramNameByIndex)
    )
    callAst(call, args)
  }

  // 一元表达式映射为操作符 CALL
  private def astForUnaryExpr(
    operatorName: String,
    expr: Expr,
    op: Value,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val call = callNode(
      node,
      expr.toString,
      operatorName,
      operatorName,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(expr.getType.toString)
    )
    val args = Seq(astForValue(op, node, locals, paramNameByIndex))
    callAst(call, args)
  }

  // 类型转换：cast(value) -> CALL + TYPE_REF
  private def astForCastExpr(
    castExpr: JCastExpr,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val call = callNode(
      node,
      castExpr.toString,
      Operators.cast,
      Operators.cast,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(castExpr.getType.toString)
    )
    val typeRefAst = Ast(typeRefNode(node, castExpr.getType.toString, castExpr.getType.toString))
    val valueAst   = astForValue(castExpr.getOp, node, locals, paramNameByIndex)
    callAst(call, Seq(typeRefAst, valueAst))
  }

  // instanceof：value + TYPE_REF
  private def astForInstanceOfExpr(
    instanceOfExpr: JInstanceOfExpr,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val call = callNode(
      node,
      instanceOfExpr.toString,
      Operators.instanceOf,
      Operators.instanceOf,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(instanceOfExpr.getType.toString)
    )
    val typeRefAst = Ast(typeRefNode(node, instanceOfExpr.getCheckType.toString, instanceOfExpr.getCheckType.toString))
    val valueAst   = astForValue(instanceOfExpr.getOp, node, locals, paramNameByIndex)
    callAst(call, Seq(valueAst, typeRefAst))
  }

  // new 对象创建（alloc）
  private def astForNewExpr(newExpr: JNewExpr, node: SootUpSimpleNode): Ast = {
    val call = callNode(
      node,
      newExpr.toString,
      Operators.alloc,
      Operators.alloc,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(newExpr.getType.toString)
    )
    Ast(call)
  }

  // new 数组创建（alloc + size）
  private def astForNewArrayExpr(
    newArrayExpr: JNewArrayExpr,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val call = callNode(
      node,
      newArrayExpr.toString,
      Operators.alloc,
      Operators.alloc,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(newArrayExpr.getType.toString)
    )
    val sizeAst = astForValue(newArrayExpr.getSize, node, locals, paramNameByIndex)
    callAst(call, Seq(sizeAst))
  }

  // new 多维数组创建（alloc + sizes）
  private def astForNewMultiArrayExpr(
    newMultiArrayExpr: JNewMultiArrayExpr,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val call = callNode(
      node,
      newMultiArrayExpr.toString,
      Operators.alloc,
      Operators.alloc,
      DispatchTypes.STATIC_DISPATCH,
      signature = Option(""),
      typeFullName = Option(newMultiArrayExpr.getType.toString)
    )
    val sizeAsts =
      newMultiArrayExpr.getSizes.asScala.map(size => astForValue(size, node, locals, paramNameByIndex)).toSeq
    callAst(call, sizeAsts)
  }

  // 数组访问：base[index] -> indexAccess
  private def astForArrayRef(
    arrayRef: JArrayRef,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val call     = operatorCallNode(node, arrayRef.toString, Operators.indexAccess, Some(arrayRef.getType.toString))
    val baseAst  = astForValue(arrayRef.getBase, node, locals, paramNameByIndex)
    val indexAst = astForValue(arrayRef.getIndex, node, locals, paramNameByIndex)
    callAst(call, Seq(baseAst, indexAst))
  }

  // 实例字段访问：base.field -> fieldAccess
  private def astForInstanceFieldRef(
    fieldRef: JInstanceFieldRef,
    node: SootUpSimpleNode,
    locals: Map[String, NewLocal],
    paramNameByIndex: Map[Int, String]
  ): Ast = {
    val fieldName = fieldRef.getFieldSignature.getName
    val baseAst   = astForValue(fieldRef.getBase, node, locals, paramNameByIndex)
    val fieldId   = fieldIdentifierNode(node, fieldName, fieldName)
    val call =
      operatorCallNode(node, s"${fieldRef.getBase}.$fieldName", Operators.fieldAccess, Some(fieldRef.getType.toString))
    callAst(call, Seq(baseAst, Ast(fieldId)))
  }

  // 静态字段访问：Type.field -> fieldAccess
  private def astForStaticFieldRef(fieldRef: JStaticFieldRef, node: SootUpSimpleNode): Ast = {
    val fieldName = fieldRef.getFieldSignature.getName
    val ownerType = fieldRef.getFieldSignature.getDeclClassType.toString
    val baseAst   = Ast(typeRefNode(node, ownerType, ownerType))
    val fieldId   = fieldIdentifierNode(node, fieldName, fieldName)
    val call =
      operatorCallNode(node, s"$ownerType.$fieldName", Operators.fieldAccess, Some(fieldRef.getType.toString))
    callAst(call, Seq(baseAst, Ast(fieldId)))
  }

  // ClassConstant 转为人可读的类名
  private def classConstantCode(constant: ClassConstant): String = {
    val raw = constant.getValue
    val normalized =
      if (constant.isRefType)
        raw.stripPrefix("L").stripSuffix(";").replace('/', '.')
      else
        raw.replace('/', '.')
    normalized
  }

  private def stmtNode(stmt: Stmt): SootUpSimpleNode = {
    val pos     = stmt.getPositionInfo.getStmtPosition
    val line    = Option.when(pos.getFirstLine >= 0)(pos.getFirstLine)
    val col     = Option.when(pos.getFirstCol >= 0)(pos.getFirstCol)
    val lineEnd = Option.when(pos.getLastLine >= 0)(pos.getLastLine)
    val colEnd  = Option.when(pos.getLastCol >= 0)(pos.getLastCol)
    SootUpSimpleNode(stmt.toString, line, col, lineEnd, colEnd)
  }

  /** 寻找 AST 中第一个可执行节点（优先选择 CALL） */
  /** 寻找 AST 中第一个可执行节点（最左子节点） */
  private def findEntryNode(node: NewNode, ast: Ast): NewNode = {
    // 递归查找 AST 中最左边的可执行子节点
    val children = ast.edges.filter(_.src == node).map(_.dst).toList
    // 过滤出可作为 CFG 节点的类型（Expression 或 ControlStructure）
    val cfgChildren = children.filter(c => c.isInstanceOf[ExpressionNew] || c.isInstanceOf[NewControlStructure])
    if (cfgChildren.isEmpty) {
      // 叶子节点：自身就是入口
      node
    } else {
      // 递归进入第一个子节点
      findEntryNode(cfgChildren.head, ast)
    }
  }

  /** 寻找 AST 入口节点（包装方法）
    *
    * @param ast
    *   要查找入口的 AST
    * @return
    *   AST 的入口节点（最左边的可执行节点）
    */
  private def findEntryNode(ast: Ast): Option[NewNode] = {
    ast.root.map(r => findEntryNode(r, ast))
  }

  /** 建立语句内部的 CFG 边（Intra-statement CFG）
    *
    * 递归遍历 AST，按照执行顺序链接子节点，确保表达式求值顺序正确。
    *
    * ==执行模型==
    * 对于 `x = foo(a, b)`，执行顺序应为：
    * {{{
    *   a ──CFG──> b ──CFG──> foo ──CFG──> assignment
    *   ↑                              │
    *   Entry                        Root
    * }}}
    *
    * ==链接规则==
    *   1. 子节点按顺序链接：`child[0] -> child[1] -> ... -> child[N]` 2. 最后一个子节点连接到父节点：`child[N] -> parent` 3. 递归处理每个子节点的内部结构
    *
    * @param ast
    *   要处理的语句 AST
    */
  private def linkInternalCfg(ast: Ast): Unit = {
    def traverse(node: NewNode): Unit = {
      val children = ast.edges.filter(_.src == node).map(_.dst)

      // 过滤出应参与 CFG 执行的节点类型
      // ExpressionNew: Call, Identifier, Literal 等
      // NewControlStructure: If, While, For 等
      val cfgChildren = children.filter(c => c.isInstanceOf[ExpressionNew] || c.isInstanceOf[NewControlStructure])

      if (cfgChildren.nonEmpty) {
        // 按顺序链接兄弟节点：child1 -> child2 -> ... -> childN
        var prevNode: Option[NewNode] = None
        cfgChildren.foreach { child =>
          // 获取子节点的入口（可能是子节点自身，也可能是其最左边的后代）
          val childEntry = findEntryNode(child, ast)
          prevNode.foreach { p =>
            // 前一个兄弟的 Root (出口) 连接到当前兄弟的 Entry (入口)
            diffGraph.addEdge(p, childEntry, EdgeTypes.CFG)
          }
          // 更新前一个节点为当前子节点（作为下一个迭代的 Root）
          prevNode = Some(child)
          // 递归处理子节点内部
          traverse(child)
        }

        // 最后一个子节点连接到父节点
        // 这确保了父节点（如 Call）在其所有参数求值后执行
        if (node.isInstanceOf[ExpressionNew] || node.isInstanceOf[NewControlStructure]) {
          prevNode.foreach { lastChild =>
            diffGraph.addEdge(lastChild, node, EdgeTypes.CFG)
          }
        }
      }
    }

    // 从 AST 根开始遍历
    ast.root.foreach(traverse)
  }

  private def createSyntheticBody(method: SootUpMethod, clazz: SootUpClass): Ast = {
    // 解析所有数据流规则：Input -> Output
    // Output 映射: None -> ReturnValue; Some(idx) -> Argument[idx]
    val flows = method.summary.flatMap { s =>
      val inputs  = parseInputIndices(s.input.getOrElse(""))
      val outputs = parseOutputIndices(s.output.getOrElse(""))
      outputs.map(out => out -> inputs)
    }

    // 聚合流向同一目标的数据源: Target -> Unique Inputs
    val groupedFlows = flows.groupMap(_._1)(_._2).map { case (k, v) => k -> v.flatten.distinct.sorted }

    var stmts = List.empty[Ast]

    // 1. 处理副作用 (Side Effects): Arg2Base, Arg2Arg
    // 生成: param_dst = <operator>.taintMerge(param_dst, param_src...)
    groupedFlows.collect {
      case (Some(targetIdx), sources) if sources.nonEmpty =>
        // 为了保留目标原有的污点状态，将目标自身也加入 Merge 源
        val allSources = (sources :+ targetIdx).distinct.sorted
        stmts :+= createTaintAssignment(method, clazz, targetIdx, allSources)
    }

    // 2. 处理返回值 (ReturnValue): Arg2Ret, Base2Ret
    // 生成: return <operator>.taintMerge(param_src...)
    val returnSources = groupedFlows.getOrElse(None, Seq.empty)
    if (returnSources.nonEmpty) {
      stmts :+= createTaintReturn(method, clazz, returnSources)
    } else {
      // 即使没有返回值流数据，为了保证 CPG 完整性，如果是无返回值的 void 方法，通常隐含一个 RET
      // 这里如果 stmts 不为空（有副作用），则无需强制加 return
    }

    if (stmts.isEmpty) {
      blockAst(NewBlock().typeFullName(Defines.Any), List.empty)
    } else {
      blockAst(NewBlock().typeFullName(Defines.Any), stmts)
    }
  }

  private def createTaintAssignment(
    method: SootUpMethod,
    clazz: SootUpClass,
    targetIdx: Int,
    sources: Seq[Int]
  ): Ast = {
    val (destName, destType) = getParamInfo(method, clazz, targetIdx)
    val destNode             = NewIdentifier().name(destName).code(destName).typeFullName(destType)

    val mergeCall = createTaintMergeCall(method, clazz, sources, destType)

    val assignment = NewCall()
      .name(Operators.assignment)
      .methodFullName(Operators.assignment)
      .code(s"$destName = ${mergeCall.root.get.asInstanceOf[NewCall].code}")
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(destType)

    Ast(assignment).withChild(Ast(destNode)).withChild(mergeCall)
  }

  private def createTaintReturn(method: SootUpMethod, clazz: SootUpClass, sources: Seq[Int]): Ast = {
    val mergeCall = createTaintMergeCall(method, clazz, sources, method.returnType)
    val code      = s"return ${mergeCall.root.get.asInstanceOf[NewCall].code}"
    Ast(NewReturn().code(code)).withChild(mergeCall)
  }

  private def createTaintMergeCall(
    method: SootUpMethod,
    clazz: SootUpClass,
    sources: Seq[Int],
    returnType: String
  ): Ast = {
    val argsInfo = sources.map(idx => getParamInfo(method, clazz, idx))
    val argsCode = argsInfo.map(_._1).mkString(", ")
    val callCode = s"<operator>.taintMerge($argsCode)"

    val callNode = NewCall()
      .name("<operator>.taintMerge")
      .methodFullName("<operator>.taintMerge")
      .code(callCode)
      .signature("ANY(ANY...)")
      .typeFullName(returnType)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)

    val children = argsInfo.map { case (name, tpe) =>
      Ast(NewIdentifier().name(name).code(name).typeFullName(tpe))
    }
    Ast(callNode).withChildren(children)
  }

  private def parseOutputIndices(outputStr: String): Seq[Option[Int]] = {
    // ReturnValue -> None
    // Argument[i] -> Some(i)
    // 粗粒度策略：忽略 Field/AccessPath，只看 Root 对象
    if (outputStr.startsWith("ReturnValue")) {
      Seq(None)
    } else {
      val pattern = "Argument\\[(-?\\d+)\\]".r
      pattern.findFirstMatchIn(outputStr).map(_.group(1).toInt).map(Some(_)).toSeq
    }
  }

  private def parseInputIndices(inputStr: String): Seq[Int] = {
    // 处理 "Argument[0]", "Argument[0..1]", "Argument[-1]"
    val pattern = "Argument\\[(-?\\d+)(\\.\\.(-?\\d+))?\\]".r
    pattern
      .findFirstMatchIn(inputStr)
      .map { m =>
        val start = m.group(1).toInt
        val end   = Option(m.group(3)).map(_.toInt).getOrElse(start)
        (start to end)
      }
      .getOrElse(Seq.empty)
  }

  private def getParamInfo(method: SootUpMethod, clazz: SootUpClass, index: Int): (String, String) = {
    if (index == -1 && !method.isStatic) {
      ("this", clazz.fullName)
    } else if (index >= 0 && index < method.parameters.size) {
      val p = method.parameters(index)
      (p.name, p.typeFullName)
    } else {
      (s"param$index", Defines.Any)
    }
  }
}
