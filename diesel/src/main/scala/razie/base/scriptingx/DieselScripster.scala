/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.base.scriptingx

import api.dwix
import javax.script.ScriptEngineManager
import jdk.nashorn.api.scripting.{ClassFilter, NashornScriptEngineFactory, ScriptObjectMirror}
import org.bson.types.ObjectId
import razie.audit.Audit
import razie.diesel.dom.ECtx
import razie.diesel.engine.DomEngECtx
import razie.tconf.DUsers
import razie.{CSTimer, Logging, csys, js}
import razie.wiki.model.WikiUsers
import scala.util.Try

/** preliminary JS scripster for embedded diesel scripts */
// todo use MiniScripster and WikiScripster when decoupled
object DieselScripster extends Logging {

 //todo deprecate - use EEFunc.newestFiddle(
  def isfiddleMap(
                     script: String,
                     lang: String,
                     q: Map[String, String],
                     typed: Option[Map[String, Any]] = None,
                     exprs:Map[String,String] = Map.empty,
                     ctx:ECtx) =
    newsfiddleMap(script, lang, q, typed, false, exprs, ctx)

  /** run a fiddle with a map of arguments "queryParms"
    *
    * @param script
    * @param lang
    * @param q
    * @param typed
    * @param doAudit
    *
    * @return (succ/fail, x.toString, x)
    */
  // todo protect calls to this
  def newsfiddleMap(
                       script: String,
                       lang: String,
                       q: Map[String, String],
                       typed: Option[Map[String, Any]] = None,
                       doAudit: Boolean = true,
                       exprs:Map[String,String] = Map.empty,
                       ctx:ECtx) : (Boolean, String, Any) = {
    val c = new CSTimer("script", "?")
    c.start("DieselScripster.newsfiddleMap")

    if (lang == "js") {

      val qj = qtojson(q)

      var expressions = exprs.map {
        t=> s"${t._1} = ${t._2} ;\n"
      }.mkString

      var jscript = s"""$expressions\n$script"""

      try {
        //        val factory = new ScriptEngineManager()
        //        val engine = factory.getEngineByName("JavaScript")

        val factory = new NashornScriptEngineFactory();
        val engine = factory.getScriptEngine(new MyCF());

        val bindings = engine.createBindings()

        // attempt to use typed bindings, if available
        q.foreach{t =>
          val v = typed.flatMap(_.get(t._1)).getOrElse(jstypeSafe(t._2))
          debug("SFIDDLE_EXEC JS bind: " + t._1 + " = " + v)

          if(v.isInstanceOf[Map[_,_]]) {
            val m = v.asInstanceOf[Map[_, _]]

            // if we have complex values, this won't work... so default back to string
            if(m.values.exists(_.isInstanceOf[List[_]]) ||
               m.values.exists(_.isInstanceOf[Map[_,_]])) {
              val ms = js.tojsons(m)
              expressions = expressions + s"${t._1} = $ms ;\n"
            } else
              bindings.put(t._1, js.toJava(v.asInstanceOf[Map[_, _]]))
          }
          else
            bindings.put(t._1, v)
        }

        jscript = s"""$expressions\n$script"""

        {
          val root = ctx.root
          val settings = root.engine.map(_.settings)
          val au = settings.flatMap(_.userId).map(new ObjectId(_))
              .flatMap(DUsers.impl.findUserById)

          val wix = razie.js toJava Map(
            "diesel" -> Map(
              "env" -> dwix.dieselEnvFor(settings.flatMap(_.realm).mkString, au),
              "user" -> settings.flatMap(_.userId).mkString
            )
          )

          // used typed values. This will resolve a "3" into a numberic 3
//          val qm = q.map(t => (t._1, typed.flatMap(_.get(t._1)).getOrElse(jstypeSafe(t._2))))
//          val qjm = razie.js toJava qm
//          bindings.put("queryParms", qjm)

          bindings.put("wixj", wix)
          bindings.put("wix", wix)
        }

        val res = engine.eval(jscript, bindings)

        if(res != null && res.isInstanceOf[ScriptObjectMirror]) {
          // return objects with nice tostring
          val json = engine.eval("JSON").asInstanceOf[ScriptObjectMirror]
          val s = json.callMember("stringify", res)
          (true, s.toString, res)
        }
        else
          (true, if (res != null) res.toString else "", res)
      } catch {
        case t: javax.script.ScriptException => {
          log(s"Exception while executing script: ${t.getMessage}\n${jscript.takeRight(300)}")
          // don't include the script body - security issue
          (false, t.toString, t)
        }
        case t: Throwable => {
          log(s"Exception while executing script\n${jscript.takeRight(300)}", t)
          // don't include the script body - security issue
            (false, t.toString, t)
        }
      } finally {

        c.stop()

        audit("xSFIDDLE_EXEC JS (in " + (c.last - c.beg) + " msec) : " + jscript.takeRight(300))
        if (doAudit)
          Audit.logdb("SFIDDLE_EXEC", "JS", (c.last - c.beg) + " msec", jscript.takeRight(300))
      }

    } else if (lang == "ruby") {

      val qj = qtojson(q)
      val jscript = "" //s"""var queryParms = $qj;\n$script"""
      try {
        val factory = new ScriptEngineManager()
        val engine = factory.getEngineByName("rb")
        val res = engine.eval(jscript)
        var bindings = engine.createBindings()
        q.foreach(t => bindings.put(t._1, typed.flatMap(_.get(t._1)).getOrElse(t._2)))
        Audit.logdb("SFIDDLE_EXEC", "ruby", jscript)
        (true, res.toString, res)
      } catch {
        case t: Throwable => {
          log(s"while executing script\n$jscript", t)
          // don't include the script body - security issue
          (false, t.toString, t)
        }
      }

    } else if (lang == "scala") {

      throw new IllegalArgumentException ("scala not supported at this point")

    } else (false, script, script)
  }

  class MyCF extends ClassFilter {
    override def exposeToScripts(s: String): Boolean = {
      if (s.startsWith("api." /* WixUtils" */)) true
        // todo wrap this to protect access to cout/csys etc
      else if (s.startsWith("java.lang.System")) true
      else {
        Audit.logdb("ERR_DENIED", "js class access denied ", s)
        false
      }
    }
  }

  /** allow for values that contain " - strip that */
  def typeSafe(v: String): String = {
    if (v.trim.startsWith("\"") || v.trim.startsWith("'") || v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try {
      v.toInt.toString
    } getOrElse {
      "'" + v + "'"
    }
  }

  /** allow for values that contain " - strip that */
  def jstypeSafe(v: String): Any = {
    if (v.trim.startsWith("\"") || v.trim.startsWith("'")) v.replaceFirst("[\"']([^\"']*)[\"']", "$1")
    else if (v.trim.startsWith("{") || v.trim.startsWith("[")) v
    else Try {
      v.toInt
    } getOrElse {
      v
    } //"'"+v+"'" }
  }

  /** old qtoj - manual, no escaping etc - bad idea */
  def qtojson1(q: Map[String, String]) = "{" + q.map(t => s"""'${t._1}' : ${typeSafe(t._2)} """).mkString(",") + "}"

  /** new qtoj - use json object via js */
  def qtojson(q: Map[String, String]) = js.tojsons(q.map(t=>(t._1, jstypeSafe(t._2))))

  def qtourl(q: Map[String, String]) = q.map(t => s"""${t._1}=${t._2}""").mkString("&")

}

