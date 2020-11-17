package is.hail.types.physical

import is.hail.annotations.{Region, UnsafeOrdering, _}
import is.hail.asm4s.Code
import is.hail.expr.ir.{EmitCodeBuilder, EmitMethodBuilder}
import is.hail.types.physical.stypes.{SBoolean, SBooleanCode}
import is.hail.types.virtual.TBoolean

case object PBooleanOptional extends PBoolean(false)
case object PBooleanRequired extends PBoolean(true)

class PBoolean(override val required: Boolean) extends PType with PPrimitive {
  lazy val virtualType: TBoolean.type  = TBoolean

  def _asIdent = "bool"

  override def _pretty(sb: StringBuilder, indent: Int, compact: Boolean): Unit = sb.append("PBoolean")

  override def unsafeOrdering(): UnsafeOrdering = new UnsafeOrdering {
    def compare(o1: Long, o2: Long): Int = {
      java.lang.Boolean.compare(Region.loadBoolean(o1), Region.loadBoolean(o2))
    }
  }

  def codeOrdering(mb: EmitMethodBuilder[_], other: PType): CodeOrdering = {
    assert(other isOfType this)
    new CodeOrderingCompareConsistentWithOthers {
      type T = Boolean

      def compareNonnull(x: Code[T], y: Code[T]): Code[Int] =
        Code.invokeStatic2[java.lang.Boolean, Boolean, Boolean, Int]("compare", x, y)
    }
  }

  override def byteSize: Long = 1

  def sType: SBoolean = SBoolean(required)

  def storePrimitiveAtAddress(cb: EmitCodeBuilder, addr: Code[Long], value: PCode): Unit = {
    cb += Region.storeBoolean(addr, value.asBoolean.boolCode(cb))
  }

  override def loadCheapPCode(cb: EmitCodeBuilder, addr: Code[Long]): PCode = new SBooleanCode(required, Region.loadBoolean(addr))
}

object PBoolean {
  def apply(required: Boolean = false): PBoolean = if (required) PBooleanRequired else PBooleanOptional

  def unapply(t: PBoolean): Option[Boolean] = Option(t.required)
}
