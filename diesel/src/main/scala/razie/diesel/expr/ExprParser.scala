/*   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.expr

import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{RDOM, WType, WTypes, XPathIdent}
import razie.diesel.ext.{BFlowExpr, FlowExpr, MsgExpr, SeqExpr}
import scala.util.parsing.combinator.RegexParsers

/**
  * expressions parser. this is a trait you can mix in your other DSL parsers,
  * see SimpleExprParser for a concrete implementation
  *
  * See http://specs.razie.com/wiki/Story:expr_story for possible expressions and examples
  */
trait ExprParser extends RegexParsers {

  def ws = whiteSpace

  def ows = opt(whiteSpace)

  //
  //=================== idents
  //

  /** a regular ident but also something in single quotes 'a@habibi.34 and - is a good ident eh' */
  def ident: Parser[String] = """[a-zA-Z_][\w]*""".r | """'[\w@. -]+'""".r ^^ {
    case s =>
      if(s.startsWith("'") && s.endsWith("'"))
        s.substring(1, s.length-1)
      else
        s
  }

  /** allow JSON ids with double quotes */
  def jsonIdent: Parser[String] = """[a-zA-Z_][\w]*""".r | """'[\w@. -]+'""".r | """"[\w@. -]+"""".r ^^ {
    case s => unquote(s)
  }

  /** qualified idents, . notation, parsed as a single string */
  def qident: Parser[String] = ident ~ rep("." ~> ident) ^^ {
    case i ~ l => (i :: l).mkString(".")
  }

  def qlident: Parser[List[String]] = qlidentDiesel | realmqlident

  // fix this somehow - these need to be accessed as this - they should be part of a "diesel" object with callbacks
  def qlidentDiesel: Parser[List[String]] = "diesel." ~ ident ^^ {
    case d ~ i => List(d+i)
  }

  /** qualified idents, . notation, parsed as a list */
  def realmqlident: Parser[List[String]] = ident ~ rep("." ~> ident) ^^ {
    case i ~ l => i :: l
  }

  def xpath: Parser[String] = ident ~ rep("[/@]+".r ~ ident) ^^ {
    case i ~ l => (i :: l.map{x=>x._1+x._2}).mkString("")
  }

  //
  //======================= MAIN: operator expressions and conditions ========================
  //

  def expr:  Parser[Expr] = exprAS | pterm1

  // a reduced expr, from boolean down
  def expr2: Parser[Expr] = exprCMP | pterm1

  def opsas: Parser[String] = "as"
  def opsmaps: Parser[String] = "map" | "flatMap" | "filter"
  def opsOR: Parser[String] = "or" | "xor"
  def opsAND: Parser[String] = "and"
  def opsCMP: Parser[String] = ">" | "<" | ">=" | "<=" | "==" | "!=" | "~="
  def opsPLUS: Parser[String] = "+" | "-" | "||" | "|"
  def opsMULT: Parser[String] = "*" | "/"

  // "1" as number
  def exprAS: Parser[Expr] = exprMAP ~ opt(ows ~> opsas ~ ows ~ pterm1) ^^ {
    case a ~ l if l.isEmpty => a
    case a ~ Some(op ~ _ ~ p) => AExpr2(a, op, p)
  }

  // x map (x => x+1)
  def exprMAP: Parser[Expr] = exprPLUS ~ rep(ows ~> opsmaps ~ ows ~ exprCMP) ^^ {
    case a ~ l => l.foldLeft(a)((x, y) =>
      y match {
        case op ~ _ ~ p => AExpr2(x, op, p)
      }
    )
  }

  // x > y
  def exprCMP: Parser[Expr] = exprPLUS ~ rep(ows ~> opsCMP ~ ows ~ exprPLUS) ^^ {
    case a ~ l => l.foldLeft(a)((x, y) =>
      y match {
        case op ~ _ ~ p => cmp(x, op, p)
      }
    )
  }

  // x + y
  def exprPLUS: Parser[Expr] = exprMULT ~ rep(ows ~> opsPLUS ~ ows ~ exprMULT) ^^ {
    case a ~ l => l.foldLeft(a)((x, y) =>
      y match {
        case op ~ _ ~ p => AExpr2(x, op, p)
      }
    )
  }

  // x * y
  def exprMULT: Parser[Expr] = pterm1 ~ rep(ows ~> opsMULT ~ ows ~ pterm1) ^^ {
    case a ~ l => l.foldLeft(a)((x, y) =>
      y match {
        case op ~ _ ~ p => AExpr2(x, op, p)
      }
    )
  }

  //
  //================== main expression rules
  //

  def pterm1: Parser[Expr] = numexpr | bcexpr | escexpr | cexpr | bcexpr | jnull | xpident | //cond |
      lambda | jsexpr2 | jsexpr1 |
      scexpr2 | scexpr1 | afunc | aidentaccess | aident | jsexpr4 |
      exregex | eblock | jarray | jobj

  //
  //==================== lambdas
  //

  // x => x + 4
  def lambda: Parser[Expr] = ident ~ ows ~ "=>" ~ ows ~ (expr2 | "(" ~> expr <~ ")") ^^ {
    case id ~ _ ~ a ~ _ ~ ex => LambdaFuncExpr(id, ex)
  }

  //
  //=================== js and json
  //

  def jsexpr1: Parser[Expr] = "js:" ~> ".*(?=[,)])".r ^^ { case li => JSSExpr(li) }
  def jsexpr2: Parser[Expr] = "js:{" ~> ".*(?=})".r <~ "}" ^^ { case li => JSSExpr(li) }
  //  def jsexpr3: Parser[Expr] = "js:{{ " ~> ".*(?=})".r <~ "}}" ^^ { case li => JSSExpr(li) }
  def scexpr1: Parser[Expr] = "sc:" ~> ".*(?=[,)])".r ^^ { case li => SCExpr(li) }
  def scexpr2: Parser[Expr] = "sc:{" ~> ".*(?=})".r <~ "}" ^^ { case li => SCExpr(li) }

  def eblock: Parser[Expr] = "(" ~ ows ~> expr <~ ows ~ ")" ^^ { case ex => BlockExpr(ex) }

  // inline js expr: //1+2//
  def jsexpr4: Parser[Expr] = "//" ~> ".*(?=//)".r <~ "//" ^^ { case li => JSSExpr(li) }

  // remove single or double quotes if any, from ID matched with them
  def unquote(s:String) =  {
    if (s.startsWith("'") && s.endsWith("\'") || s.startsWith("\"") && s
        .endsWith("\""))
      s.substring(1, s.length - 1)
    else
      s
  }

  def jnull: Parser[Expr] = "null" ^^ {
    case b => new CExprNull
  }

  // json object - sequence of nvp assignemnts separated with commas
  def jobj: Parser[Expr] = "{" ~ ows ~> repsep(jnvp <~ ows, ",") <~ ows ~ "}" ^^ {
    case li => JBlockExpr(li)
  }

  // one json block nvp pair
  def jnvp: Parser[(String, Expr)] = ows ~> jsonIdent ~ " *[:=] *".r ~ jexpr ^^ {
    case name ~ _ ~ ex =>  (unquote(name), ex)
  }

  def jarray: Parser[Expr] = "[" ~ ows ~> repsep(ows ~> jexpr <~ ows, ",") <~ ows ~ "]" ^^ {
    case li => JArrExpr(li) //CExpr("[ " + li.mkString(",") + " ]")
  }

  def jexpr: Parser[Expr] = jobj | jarray | jbool | jother ^^ { case ex => ex } //ex.toString }

  def jbool: Parser[Expr] = ("true" | "false") ^^ {
    case b => new CExpr(b, WTypes.wt.BOOLEAN)
  }

  //  def jother: Parser[String] = "[^{}\\[\\],]+".r ^^ { case ex => ex }
  def jother: Parser[Expr] = expr ^^ { case ex => ex }

  // a number
  def numexpr: Parser[Expr] = (afloat | aint ) ^^ { case i => new CExpr(i, WTypes.wt.NUMBER) }

  def aint: Parser[String] = """-?\d+""".r
  def afloat: Parser[String] = """-?\d+[.]\d+""".r

  // string const with escaped chars
  def cexpr: Parser[Expr] = "\"" ~> """(\\.|[^\"])*""".r <~ "\"" ^^ {
    e => new CExpr(e.replaceAll("\\\\(.)", "$1"), WTypes.wt.STRING)
  }

  // escaped multiline string const with escaped chars
  // we're removing the first \n
  def escexpr: Parser[Expr] = "\"\"\"" ~ opt("\n") ~> """(?s)((?!\"\"\").)*""".r <~ "\"\"\"" ^^ {
    e => new CExpr(e.replaceAll("\\\\(.)", "$1"), WTypes.wt.STRING)
  }

  def bcexpr: Parser[Expr] = ("true" | "false") ^^ {
    b => new CExpr(b, WTypes.wt.BOOLEAN)
  }

  // XP identifier (either json or xml)
  def xpident: Parser[Expr] = "xp:" ~> xpath ^^ { case i => new XPathIdent(i) }

  // regular expression, JS style
  def exregex: Parser[Expr] =
    """/[^/]*/""".r ^^ { case x => new CExpr(x, WTypes.wt.REGEX) }

  //
  //==================================== ACCESSORS
  //

  // qualified identifier
  def aident: Parser[AExprIdent] = qlident ^^ { case i => new AExprIdent(i.head, i.tail.map(P("", _))) }

  // simple qident or complex one
  def aidentExpr: Parser[AExprIdent] = aidentaccess | aident

  // full accessor to value: a.b[4].c.r["field1"]["subfield2"][4].g
  // note this kicks in at the first use of [] and continues... so that aident above catches all other

  def aidentaccess: Parser[AExprIdent] = qlident ~ (sqbraccess | sqbraccessRange | accessorNum) ~ accessors ^^ {
    case i ~ sa ~ a => new AExprIdent(i.head, i.tail.map(P("", _)) ::: sa :: a)
  }

  def accessors: Parser[List[RDOM.P]] = rep(sqbraccess | sqbraccessRange | accessorIdent | accessorNum)

  private def accessorIdent: Parser[RDOM.P] = "." ~> ident ^^ {case id => P("", id, WTypes.wt.STRING)}

  private def accessorNum: Parser[RDOM.P] = "." ~> "[0-9]+".r ^^ {case id => P("", id, WTypes.wt.NUMBER)}

  private def sqbraccess: Parser[RDOM.P] = "\\[".r ~> ows ~> expr <~ ows <~ "]" ^^ {
    case e => P("", "").copy(expr=Some(e))
  }
  // for now the range is only numeric
  private def sqbraccessRange: Parser[RDOM.P] = "\\[".r ~> ows ~> numexpr ~ ows ~ ".." ~ ows ~ opt(numexpr) <~ ows <~ "]" ^^ {
    case e1 ~ _ ~ _ ~ _ ~ e2 => P("", "", WTypes.wt.RANGE).copy(
      expr = Some(ExprRange(e1, e2))
    )
  }


  //
  //==================================== F U N C T I O N S
  //

  def afunc: Parser[Expr] = qident ~ attrs ^^ { case i ~ a => AExprFunc(i, a) }

  def optKinds: Parser[Option[String]] = opt(ows ~> "[" ~> ows ~> repsep(ident, ",") <~ "]") ^^ {
    case Some(tParm) => Some(tParm.mkString)
    case None => None
  }

  /**
    * expr assignment, left side can be a[5].name
    */
  def pasattr: Parser[PAS] = " *".r ~> (aidentaccess | aident) ~ opt(" *= *".r ~> expr) ^^ {
    case ident ~ e => {
      e match {
        case Some(ex) => PAS(ident, ex)
        case None => PAS(ident, ident) // compatible for a being a=a
      }
    }
  }

  /**
    * simple ident = expr assignemtn when calling
    */
  def pcallattrs: Parser[List[RDOM.P]] = " *\\(".r ~> ows ~> repsep(pcallattr, ows ~ "," ~ ows) <~ ows <~ ")"
  def pcallattr: Parser[P] = " *".r ~> qident ~ opt(" *= *".r ~> expr) ^^ {
    case ident ~ ex => {
      P(ident, "", ex.map(_.getType).getOrElse(WTypes.wt.EMPTY), ex)
    }
  }

  def pasattrs: Parser[List[PAS]] = " *\\(".r ~> ows ~> repsep(pasattr, ows ~ "," ~ ows) <~ ows <~ ")"

  /**
    * :<>type[kind]*
    * <> means it's a ref, not ownership
    * * means it's a list
    */
  def optType: Parser[WType] = opt(" *: *".r ~> opt("<>") ~ ident ~ optKinds ~ opt(" *\\* *".r)) ^^ {
    case Some(ref ~ tt ~ k ~ None) => WType(tt, "", k).withRef(ref.isDefined)
    case Some(ref ~ tt ~ k ~ Some(_)) => WType(WTypes.ARRAY, "", Some(tt)).withRef(ref.isDefined)
    case None => WTypes.wt.EMPTY
  }

  /**
    * name:<>type[kind]*~=default
    * <> means it's a ref, not ownership
    * * means it's a list
    */
  def pattr: Parser[RDOM.P] = " *".r ~> qident ~ optType ~ opt(" *~?= *".r ~> expr) ^^ {

    case name ~ t ~ e => {
      val (dflt, ex) = e match {
        //        case Some(CExpr(ee, "String")) => (ee, None)
        // todo good optimization but I no longer know if some parm is erased like (a="a", a="").
        case Some(expr) => ("", Some(expr))
        case None => ("", None)
      }
      t match {
        // k - kind is [String] etc
        case WTypes.wt.EMPTY => // infer type from expr
          P(name, dflt, ex.map(_.getType).getOrElse(WTypes.wt.EMPTY), ex)
        case tt => // ref or no archetype
          P(name, dflt, tt, ex)
      }
    }
  }

  /**
    * optional attributes
    */
  def optAttrs: Parser[List[RDOM.P]] = opt(attrs) ^^ {
    case Some(a) => a
    case None => List.empty
  }

  /**
    * optional attributes
    */
  def attrs: Parser[List[RDOM.P]] = " *\\(".r ~> ows ~> repsep(pattr, ows ~ "," ~ ows) <~ ows <~ ")"


  //
  //==================================== C O N D I T I O N S
  //

  def cond: Parser[BoolExpr] = orexpr

  def orexpr: Parser[BoolExpr] = bterm1 ~ rep(ows ~> ("or") ~ ows ~ bterm1 ) ^^ {
    case a ~ l => l.foldLeft(a)((a, b) =>
      b match {
        case op ~ _ ~ p => bcmp (a, op, p)
      }
    )
  }

  def bterm1: Parser[BoolExpr] = bfactor1 ~ rep(ows ~> ("and") ~ ows ~ bfactor1 ) ^^ {
    case a ~ l => l.foldLeft(a)((a, b) =>
      b match {
        case op ~ _ ~ p => bcmp (a, op, p)
      }
    )
  }

  def bfactor1: Parser[BoolExpr] = notbfactor1 | bfactor2

  def notbfactor1: Parser[BoolExpr] = ows ~> ("not" | "NOT") ~> ows ~> bfactor2 ^^ { BCMPNot }

  def bfactor2: Parser[BoolExpr] = bConst | eq | neq | lte | gte | lt | gt | like | bvalue | condBlock

  private def condBlock: Parser[BoolExpr] = ows ~> "(" ~> ows ~> cond <~ ows <~ ")" ^^ { BExprBlock }

  private def cmp(a: Expr, s: String, b: Expr) = new BCMP2(a, s, b)

  private def ibex(op: => Parser[String]) : Parser[BoolExpr] = expr ~ (ows ~> op <~ ows) ~ expr ^^ {
    case a ~ s ~ b => cmp(a, s.trim, b)
  }

  /** true or false constants */
  def bConst: Parser[BoolExpr] = ("true" | "false") ^^ { BCMPConst }

  def eq: Parser[BoolExpr]   = ibex("==" | "is")
  def neq: Parser[BoolExpr]  = ibex("!=" | "not")
  def like: Parser[BoolExpr] = ibex("~=" | "matches")
  def lte: Parser[BoolExpr]  = ibex("<=")
  def gte: Parser[BoolExpr]  = ibex(">=")
  def lt: Parser[BoolExpr]   = ibex("<")
  def gt: Parser[BoolExpr]   = ibex(">")

  // default - only used in PM, not conditions
  def df: Parser[BoolExpr]   = ibex("?=")

  /** single value expressions, where != 0 is true and != null is true */
  def bvalue : Parser[BoolExpr] = expr ^^ {
    case a => BCMPSingle(a)
  }

  private def bcmp(a: BoolExpr, s: String, b: BoolExpr) = new BCMP1(a, s, b)


  //
  // ---------------------- flow expressions
  //

  def flowexpr: Parser[FlowExpr] = seqexpr

  def seqexpr: Parser[FlowExpr] = parexpr ~ rep(ows ~> ("+" | "-") ~ ows ~ parexpr) ^^ {
    case a ~ l =>
      SeqExpr("+", a :: l.collect {
        case op ~ _ ~ p => p
      })
  }

  def parexpr: Parser[FlowExpr] = parterm1 ~ rep(ows ~> ("|" | "||") ~ ows ~ parterm1) ^^ {
    case a ~ l =>
      SeqExpr("|", a :: l.collect {
        case op ~ _ ~ p => p
      })
  }

  def parterm1: Parser[FlowExpr] = parblock | msgterm1

  def parblock: Parser[FlowExpr] = "(" ~ ows ~> seqexpr <~ ows ~ ")" ^^ {
    case ex => BFlowExpr(ex)
  }

  def msgterm1: Parser[FlowExpr] = qident ^^ { case i => new MsgExpr(i) }

}