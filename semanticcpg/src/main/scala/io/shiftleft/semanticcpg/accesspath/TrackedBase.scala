package io.shiftleft.semanticcpg.accesspath

import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

trait TrackedBase
case class TrackedNamedVariable(name: String) extends TrackedBase
case class TrackedReturnValue(call: CallRepr) extends TrackedBase {
  override def toString: String = {
    s"TrackedReturnValue(${call.code})"
  }
}
case class TrackedLiteral(literal: Literal) extends TrackedBase {
  override def toString: String = {
    s"TrackedLiteral(${literal.code})"
  }
}

sealed trait TrackedMethodOrTypeRef extends TrackedBase {
  def code: String

  override def toString: String = {
    s"TrackedMethodOrTypeRef($code)"
  }
}

case class TrackedMethod(method: MethodRef) extends TrackedMethodOrTypeRef {
  override def code: String = method.code
}
case class TrackedTypeRef(typeRef: TypeRef) extends TrackedMethodOrTypeRef {
  override def code: String = typeRef.code
  private lazy val typeKey: String = {
    typeRef.evalTypeOut.headOption.map(_.fullName).getOrElse {
      val fullName = Option(typeRef.typeFullName).getOrElse("")
      if (fullName.nonEmpty && fullName != "<empty>") fullName else typeRef.code
    }
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: TrackedTypeRef =>
        typeKey == other.typeKey
      case _ =>
        false
    }
  }

  override def hashCode(): Int = {
    typeKey.hashCode()
  }
}

case class TrackedAlias(argIndex: Int) extends TrackedBase {
  override def toString: String = {
    s"TrackedAlias($argIndex)"
  }
}

object TrackedUnknown extends TrackedBase {
  override def toString: String = {
    "TrackedUnknown"
  }
}
object TrackedFormalReturn extends TrackedBase {
  override def toString: String = {
    "TrackedFormalReturn"
  }
}
