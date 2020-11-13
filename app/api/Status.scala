package api

import javax.inject.Inject
import com.mohiva.play.silhouette.api.Authenticator.Implicits._
import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.util.{Clock, Credentials}
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import com.mohiva.play.silhouette.impl.providers._
import play.api.Logger
import models.User
import play.api.Play._
import play.api.libs.json.{JsValue, Json}
import services._
import services.mongodb.MongoSalatPlugin

import scala.collection.mutable

/**
 * class that contains all status/version information about clowder.
 */
class Status @Inject()(spaces: SpaceService,
                       collections: CollectionService,
                       datasets: DatasetService,
                       files: FileService,
                       users: UserService,
                       appConfig: AppConfigurationService,
                       extractors: ExtractorService,
                       versusService: VersusService,
                       searches: SearchService) extends ApiController {
  val jsontrue = Json.toJson(true)
  val jsonfalse = Json.toJson(false)

  def version = UserAction(needActive=false) { implicit request =>
    Ok(Json.obj("version" -> getVersionInfo))
  }

  def status = silhouette.SecuredAction(WithProvider[DefaultEnv#A](CredentialsProvider.ID)) { implicit request =>

    Ok(Json.obj("version" -> getVersionInfo,
      "counts" -> getCounts(request.user),
      "plugins" -> getPlugins(request.user),
      "extractors" -> Json.toJson(extractors.getExtractorNames(List.empty))))
  }

  def getPlugins(user: Option[User]): JsValue = {
    val result = new mutable.HashMap[String, JsValue]()

    if (searches.isEnabled()) {
      result.put("elasticsearch", if (Permission.checkServerAdmin(user)) {
        searches.getInformation()
      } else Json.obj({ "status" -> "connected"}))
    } else {
      result.put("elasticsearch", Json.obj("status" -> "disconnected"))
    }

    current.plugins foreach {
      // mongo
      case p: MongoSalatPlugin => {
        result.put("mongo", if (Permission.checkServerAdmin(user)) {
              Json.obj("uri" -> p.mongoURI.toString(),
                "updates" -> appConfig.getProperty[List[String]]("mongodb.updates", List.empty[String]))
            } else {
              jsontrue
            })
      }

      // versus
        result.put("versus", if (Permission.checkServerAdmin(user)) {
          Json.obj("host" -> configuration.getString("versus.host").getOrElse("").toString)
        } else {
          jsontrue
        })

      case p => {
        val name = p.getClass.getName
        if (name.startsWith("services.")) {
          val status = if (p.enabled) {
            "enabled"
          } else {
            "disabled"
          }
          result.put(p.getClass.getName, Json.obj("status" -> status))
        } else {
          Logger.debug(s"Ignoring ${name} plugin")
        }
      }
    }

    Json.toJson(result.toMap[String, JsValue])
  }

  def getCounts(user: Option[User]): JsValue = {
    val counts = appConfig.getIndexCounts()
    // TODO: Revisit this check as it is currently too slow
    //val fileinfo = if (Permission.checkServerAdmin(user)) {
    //  Json.toJson(files.statusCount().map{x => x._1.toString -> Json.toJson(x._2)})
    //} else {
    //  Json.toJson(counts.numFiles)
    //}
    val fileinfo = counts.numFiles
    Json.obj("spaces" -> counts.numSpaces,
      "collections" -> counts.numCollections,
      "datasets" -> counts.numDatasets,
      "files" -> fileinfo,
      "bytes" -> counts.numBytes,
      "users" -> counts.numUsers)
  }

  def getVersionInfo: JsValue = {
    val sha1 = sys.props.getOrElse("build.gitsha1", default = "unknown")

    // TODO use the following URL to indicate if there updates to clowder.
    // if returned object has an empty values clowder is up to date
    // need to figure out how to pass in the branch
    //val checkurl = "https://opensource.ncsa.illinois.edu/stash/rest/api/1.0/projects/CATS/repos/clowder/commits?since=" + sha1

    Json.obj("number" -> sys.props.getOrElse("build.version", default = "0.0.0").toString,
      "build" -> sys.props.getOrElse("build.bamboo", default = "development").toString,
      "branch" -> sys.props.getOrElse("build.branch", default = "unknown").toString,
      "gitsha1" -> sha1)
  }
}
