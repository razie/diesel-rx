package razie.diesel.dom

import razie.clog
import razie.diesel.ext.CanHtml

import scala.collection.mutable
import scala.util.Try

//------------ expressions and conditions

/** deserialization is assumed via DSL
  *
  *  the idea is that all activities would have an external DSL form as well
  *  and can serialize themselves in that form
  *
  *  serialize the DEFINITION only - not including states/values
  */
trait HasDsl /*extends GReferenceable*/ {
  def serialize : String = toDsl

  /** serialize the DEFINITION - not including
    */
  def toDsl : String
  def toIndentedDsl (indent:Int=>String, level:Int) = indent(level) + toDsl
}

/**
 * Basic executable/actionable interface. These process a default input value and return a default output value.
 *
 * They are also invoked in a context - a set of objects in a certain role.
 *
 * There are two major branches: WFunc and WfActivity. An action is a workflow specific thing and is aware of next actions, state of execution whatnot. It also does something so it's derived from WFunc.
 *
 * WFunc by itself only does something and is not stateful. Most activities are like that.
 */
trait WFunc { // extends PartialFunc ?
  /** apply this function to an input value and a context */
  def apply (v:Any)(implicit ctx:ECtx) : Any
  /** what is the resulting type - when known */
  def getType : String = ""
}

/** an expression */
abstract class Expr extends WFunc with HasDsl with CanHtml {
  def expr : String
  override def toString = toDsl
  override def toDsl = expr
  override def toHtml = tokenValue(toDsl)
}

/** arithmetic expressions */
case class AExpr2 (a:Expr, op:String, b:Expr) extends Expr {
  val expr = (a.toDsl + op + b.toDsl)

  override def apply(v: Any)(implicit ctx: ECtx) = //Try {
    op match {
      case "+" => {
        (a, b) match {
            // json exprs are different
          case (AExprIdent(aid), JBlockExpr(jb)) if ctx.getp(aid).exists(_.ttype == WTypes.JSON) =>
            jsonExpr(op, a(v).toString, b(v).toString)

          case _ => {
            a(v).toString + b(v).toString
          }
        }
      }

      case "||" if a.isInstanceOf[AExprIdent] => {
        a match {
          case AExprIdent(aid) =>
            ctx.getp(aid).map(_.dflt).getOrElse(b(v).toString)

          case _ => {
          }
        }
      }

      case _ => "[ERR unknown operator " + op + "]"
    }
//  }.recover {
//    case t:Throwable => {
//      clog << t
//      t.toString
//    }
//  }.get

  /** process a js operation */
  def jsonExpr (op:String, aa:String, bb:String) = {
    val ai = razie.js.parse(aa)
    val bi = razie.js.parse(bb)
    val res = new mutable.HashMap[String,Any]()
    ai.foreach{t => res.put(t._1, t._2)}
    bi.foreach{t =>
      val k = t._1
      val bv = t._2
      if(res.contains(k)) {
        val ax = res(k)
        ax match {
          case al : List[_] => {
            bv match {
              case bll : List[_] => res.put(k, al ::: bll)
              case _ => res.put(k, al ::: bv :: Nil)
            }
          }
          case m : Map[_, _] => {
            val mres = new mutable.HashMap[String,Any]()
            m.foreach{t => mres.put(t._1.toString,t._2)}
            res.put(k,mres)
          }
          case y @ _ => res.put(k, y.toString + bv.toString)
        }
      } else res.put(k,bv)
    }
    razie.js.tojsons(res.toMap)
  }

  override def getType = b.getType
}

/** a qualified identifier */
case class AExprIdent (val expr:String) extends Expr {
  override def apply (v:Any)(implicit ctx:ECtx) = ctx.apply(expr)
}

/** constant expression
  *
  *  TODO i'm loosing the type definition
  */
case class CExpr (ee : String, ttype:String="") extends Expr {
  val expr = ee.toString
  override def apply (v:Any)(implicit ctx:ECtx) = if(ttype == "Number") ee.toInt else ee
  override def toDsl = if(ttype == "String") ("\"" + expr + "\"") else expr
  override def getType: String = ttype
  override def toHtml = tokenValue(toDsl)
}


/** arithmetic expressions */
case class AExpr (val expr : String) extends Expr {
  override def apply (v:Any)(implicit ctx:ECtx) = v
}

/** a block */
case class BlockExpr (ex : Expr) extends Expr {
  val expr = "( " + ex.toString + " )"
  override def apply (v:Any)(implicit ctx:ECtx) = ex.apply(v)
  override def getType: String = ex.getType
}

/** a json block */
case class JBlockExpr (ex : String) extends Expr {
  val expr = "{ " + ex.toString + " }"

  override def apply (v:Any)(implicit ctx:ECtx) = template(expr)

  override def getType: String = WTypes.JSON

  // replace ${e} with value
  def template (s:String)(implicit ctx:ECtx) = {
    val PATT = """(\$\w+)""".r
    val u = PATT.replaceSomeIn(s, { m =>
      val n = if(m.matched.length > 0) m.matched.substring(1) else ""
      ctx.get(n).map(x=>
        razie.diesel.ext.stripQuotes(x)
      )
    })
    u
  }

}

// exprs

/** boolean expressions */
abstract class BExpr(e: String) extends HasDsl {
  def apply(e: Any)(implicit ctx: ECtx): Boolean

  override def toDsl = e
}

/** negated boolean expression */
case class BCMPNot(a: BExpr) extends BExpr("") {
  override def apply(e: Any)(implicit ctx: ECtx) = !a.apply(e)
}

/** composed boolean expression */
case class BCMP1(a: BExpr, op: String, b: BExpr) extends BExpr(a.toDsl + " " + op + " " + b.toDsl) {
  override def apply(in: Any)(implicit ctx: ECtx) = op match {
    case "||" => a.apply(in) || b.apply(in)
    case "&&" => a.apply(in) && b.apply(in)
    case _ => {
      clog << "[ERR Operator " + op + " UNKNOWN!!!]"; false
    }
  }

  override def toString = a.toString + " " + op + " " + b.toString
}

/** simple boolean expression */
case class BCMP2(a: Expr, op: String, b: Expr) extends BExpr(a.toDsl + " " + op + " " + b.toDsl) {
  override def apply(in: Any)(implicit ctx: ECtx) = {
    (a, b) match {
      case (CExpr(aa, WTypes.NUMBER), CExpr(bb, WTypes.NUMBER)) => {
        val ai = aa.toInt
        val bi = bb.toInt
        op match {
          case "?=" => true
          case "==" => ai == bi
          case "!=" => ai != bi
          case "<=" => ai <= bi
          case ">=" => ai >= bi
          case "<" => ai < bi
          case ">" => ai > bi
          case _ => {
            clog << "[ERR Operator " + op + " UNKNOWN!!!]";
            false
          }
        }
      }
      case _ => {
        val as = a(in).toString
        val bs = b(in).toString
        val x = as matches bs
        op match {
          case "?=" => a(in).toString.length >= 0 // anything with a default
          case "==" => a(in) == b(in)
          case "!=" => a(in) != b(in)
          case "~=" => a(in).toString matches b(in).toString
          case "<=" => a(in).toString <= b(in).toString
          case ">=" => a(in).toString >= b(in).toString
          case "<" => a(in).toString < b(in).toString
          case ">" => a(in).toString > b(in).toString
          case "contains" => a(in).toString contains b(in).toString
          case "is" => {
            // is nuber or is date or is string etc
            a.isInstanceOf[CExpr] && b.isInstanceOf[AExprIdent] && (
              a.asInstanceOf[CExpr].ttype.toLowerCase == b.asInstanceOf[AExprIdent].expr.toLowerCase ||
                "number" == b.asInstanceOf[AExprIdent].expr.toLowerCase && a.asInstanceOf[CExpr].expr.matches("[0-9]+")
              ) ||
              (a(in).toString == b(in).toString)
            // if not known type expr, then behave like equals
          }
          case _ => {
            clog << "[ERR Operator " + op + " UNKNOWN!!!]";
            false
          }
        }
      }
    }
  }
}

