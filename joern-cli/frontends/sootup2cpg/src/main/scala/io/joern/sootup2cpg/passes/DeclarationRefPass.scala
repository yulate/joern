package io.joern.sootup2cpg.passes

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.{EdgeTypes, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.{Declaration, FieldIdentifier, Method, TypeDecl, TypeRef}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

/** 将标识符与其声明节点建立 REF 边。
  *
  * 由于字节码 AST 较扁平，这里直接在方法级别进行链接。
  */
class DeclarationRefPass(cpg: Cpg) extends ForkJoinParallelCpgPass[Method](cpg) {

  override def generateParts(): Array[Method] = cpg.method.toArray

  override def runOnPart(builder: DiffGraphBuilder, part: Method): Unit = {
    val identifiers  = part.ast.isIdentifier.toList
    val declarations = (part.parameter ++ part.block.astChildren.isLocal).collectAll[Declaration].l
    declarations.foreach(d => identifiers.nameExact(d.name).foreach(builder.addEdge(_, d, EdgeTypes.REF)))

    // TypeRef -> TypeDecl 绑定
    part.ast.isTypeRef.foreach { typeRef =>
      cpg.typeDecl.fullNameExact(typeRef.typeFullName).headOption.foreach { typeDecl =>
        builder.addEdge(typeRef, typeDecl, EdgeTypes.REF)
      }
    }

    // 字段访问：FieldIdentifier -> Member 绑定（基于 base 类型）
    part.ast.isCall.nameExact(Operators.fieldAccess).foreach { fieldAccess =>
      val args = fieldAccess.argument.l
      val baseTypeOpt = args.headOption.flatMap {
        case tr: TypeRef => Option(tr.typeFullName)
        case id: io.shiftleft.codepropertygraph.generated.nodes.Identifier => Option(id.typeFullName)
        case _ => None
      }
      val fieldIdentOpt = args.lift(1).collect { case f: FieldIdentifier => f }
      for {
        baseType <- baseTypeOpt
        fieldIdent <- fieldIdentOpt
        typeDecl <- cpg.typeDecl.fullNameExact(baseType).headOption
        member <- typeDecl.member.nameExact(fieldIdent.canonicalName).headOption
      } builder.addEdge(fieldIdent, member, EdgeTypes.REF)
    }
  }
}
