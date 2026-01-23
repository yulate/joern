package io.joern.sootup2cpg.sootup

/** 统一的 SootUp 语义模型（用于 AST 构建）。
  *
  * 注意：当前模型是 sootup2cpg 的内部抽象层，后续会由 SootUp 真实对象填充。
  */
object SootUpModel {

  trait SootUpNode {
    def code: String
    def lineNumber: Option[Int]      = None
    def columnNumber: Option[Int]    = None
    def lineNumberEnd: Option[Int]   = None
    def columnNumberEnd: Option[Int] = None
  }

  final case class SummaryEntry(
    classFullName: String,
    methodName: String,
    paramTypes: Option[Seq[String]],
    isStatic: Boolean,
    input: Option[String] = None,
    output: Option[String] = None,
    kind: Option[String] = None
  )

  final case class SootUpSimpleNode(
    code: String,
    override val lineNumber: Option[Int] = None,
    override val columnNumber: Option[Int] = None,
    override val lineNumberEnd: Option[Int] = None,
    override val columnNumberEnd: Option[Int] = None
  ) extends SootUpNode

  final case class SootUpProject(classes: Seq[SootUpClass])

  final case class SootUpClass(
    name: String,
    fullName: String,
    packageName: String,
    filename: String,
    // 库代码与应用代码区分，用于控制是否生成方法体/语义
    isExternal: Boolean = false,
    inheritsFrom: Seq[String] = Seq.empty,
    fields: Seq[SootUpField] = Seq.empty,
    methods: Seq[SootUpMethod] = Seq.empty,
    // 反编译的源码内容
    sourceCode: Option[String] = None
  ) extends SootUpNode {
    override def code: String = name
  }

  final case class SootUpField(name: String, typeFullName: String, isStatic: Boolean = false) extends SootUpNode {
    override def code: String = s"$typeFullName $name"
  }

  final case class SootUpMethod(
    name: String,
    fullName: String,
    signature: String,
    returnType: String,
    parameters: Seq[SootUpParam] = Seq.empty,
    isStatic: Boolean = false,
    // 标记库方法：只保留签名，不构建方法体
    isExternal: Boolean = false,
    // 方法体中的局部变量与语句（Jimple 层面）
    locals: Seq[sootup.core.jimple.basic.Local] = Seq.empty,
    stmts: Seq[sootup.core.jimple.common.stmt.Stmt] = Seq.empty,
    // 异常处理入口：handler 语句索引 -> 异常类型列表
    exceptionHandlers: Map[Int, Seq[String]] = Map.empty,
    // 控制流图 (新增)
    graph: Option[Any] = None,
    // 注解信息 (新增): 存储方法的注解列表，用于生成 CPG ANNOTATION 节点
    annotations: Seq[String] = Seq.empty,
    // CodeQL Summary 信息
    summary: Seq[SummaryEntry] = Seq.empty
  ) extends SootUpNode {
    override def code: String = s"$name$signature"
  }

  final case class SootUpParam(name: String, typeFullName: String, order: Int) extends SootUpNode {
    override def code: String = s"$typeFullName $name"
  }
}
