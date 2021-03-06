/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki

import com.google.inject.Inject
import controllers.{NoWikiAuthorization, WikiAuthorization}
import razie.db.RazMongo
import razie.wiki.model._
import razie.wiki.util.{AuthService, NoAuthService}

/** central point of customization - aka service registry
  *
  * todo use some proper injection pattern - this is not MT-safe
  *
  * right now this is setup in Global and different Module(s), upon startup
  */
object Services {

  @Inject() var auth: AuthService[WikiUser] = NoAuthService
  @Inject() var config: WikiConfig = new SampleConfig
  @Inject() var wikiAuth: WikiAuthorization = new NoWikiAuthorization

  // this is only used for signed scripts - unsafe scripts are not ran here
  var runScriptImpl : (String, String, Option[WikiEntry], Option[WikiUser], Map[String, String], Boolean) => String =
    (script: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean) =>
      "TODO customize scripster"

  var mkReactor : (String, List[Reactor], Option[WikiEntry]) => Reactor = { (realm, fallBack, we)=>
//    new ReactorImpl(realm, fallBack, we)
     throw new IllegalArgumentException("Services.mkReactor implementation needed")
  }

  /** run the given script in the context of the given page and user as well as the query map */
  def runScript (s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], devMode:Boolean=false): String =
    runScriptImpl(s,lang, page,user,query,devMode)

  def noInitSample = {
    /** connect to your database, with your connection properties, clustered or not etc */
    import com.mongodb.casbah.MongoConnection
    RazMongo.setInstance {
      MongoConnection("") apply ("")
    }
  }

  /** is this website trusted? if not links will have a "exit" warning */
  var isSiteTrusted : (String,String) => Boolean = {(r,s)=>false }


  /** initialize the event processor */
  def initCqrs (al:EventProcessor) = BasicServices.initCqrs(al)

  /** CQRS dispatcher */
  def ! (a: Any) = BasicServices ! a
}


