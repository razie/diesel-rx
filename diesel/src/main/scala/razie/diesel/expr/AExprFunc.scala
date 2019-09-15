package razie.diesel.expr

import razie.diesel.dom.RDOM.P
import razie.diesel.dom._
import razie.diesel.engine.DomEngine
import razie.diesel.exec.EEFunc
import razie.diesel.ext.EMsg
import razie.wiki.parser.SimpleExprParser

/** a function call */
case class AExprFunc(val expr: String, parms: List[RDOM.P]) extends Expr {

  override def apply(v: Any)(implicit ctx: ECtx) = applyTyped(v).calculatedValue
  override def applyTyped(v: Any)(implicit ctx: ECtx): P = {
    expr match {
      case "sizeOf" => {
        parms.headOption
          .flatMap { p =>
              // maybe the first parm is the accessor expression (lambda parm like)
            val pv = if (p.name.contains(".") || p.name.contains("[")) {
              (new SimpleExprParser).parseIdent(p.name).flatMap(_.tryApplyTyped(v))
            } else if(p.dflt.isEmpty && p.expr.isEmpty) {
              // sizeOf(payload)
              ctx.getp(p.name) // don't care about names, just get the first parm and evalueate
            } else {
              // nope - it's just a normal parm=expr
              Some(p)
            }
            pv
          }.map { p =>
            val pv = p.calculatedTypedValue

            if (pv.contentType == WTypes.ARRAY) {
              val sz = pv.asArray.size
              P("", sz.toString, WTypes.NUMBER).withValue(sz, WTypes.NUMBER)
            } else if (pv.contentType == WTypes.JSON) {
              val sz = pv.asJson.size
              P("", sz.toString, WTypes.NUMBER).withValue(sz, WTypes.NUMBER)
            } else {
              throw new DieselExprException(
                "Not array: " + p.name + " is:" + pv.toString
              )
            }
          }
          .getOrElse(
            throw new DieselExprException("No arguments for sizeOf")
          )
      }

      case _ => {
        val spec = ctx.root.domain.flatMap {
          _.moreElements.collect {
            case s: EMsg if s.ea == expr => Some(s)
          }.headOption
        }

        val func = ctx.root.domain.flatMap {
          _.funcs.get (expr)
        }

        val PAT = """([\w.]+)[./](\w+)""".r
        val PAT(ee, aa) = expr
        val msg = EMsg(ee, aa, parms)
        val ast = DomAst(msg, AstKinds.RECEIVED)

        spec.flatMap { msgSpec =>
          ctx.root.engine.flatMap{engine=>
              val newe = new DomEngine(
                engine.dom,
                ast,
                engine.settings,
                engine.pages,
                "SYNC-"+engine.description
              )

            val level =
              if(ctx.isInstanceOf[StaticECtx])
                ctx.asInstanceOf[StaticECtx].curNode.flatMap(n=>
                  ctx.root.engine.map(_.findLevel(n))
                ).getOrElse(0)
            else
                0

            // a message with this name found, call it sync
            val res = newe.execSync(ast, level, ctx)

            ast.setKinds(AstKinds.TRACE)
            ast.kind = AstKinds.SUBTRACE

            // save the trace in the main tree
            if(ctx.isInstanceOf[StaticECtx])
              ctx.asInstanceOf[StaticECtx].curNode.foreach(_.children.append(
                ast
              ))

            res
          }
        } orElse func.map {f=>
          EEFunc.exec(msg, f)
        } getOrElse {
          throw new DieselExprException("Function/Message not found: " + expr)
        }
      }
    }

  }

  override def toDsl = expr + "(" + parms.mkString(",") + ")"
  override def toHtml = tokenValue(toDsl)
}

