/**
 *
 */
package controllers

import play.api.mvc.Controller
import services.Services
import play.api.Logger
import play.api.mvc.Action
import play.api.data.Form
import play.api.data.Forms._
import views.html.defaultpages.error
import java.io.FileInputStream
import play.api.Play.current
import services.RabbitmqPlugin
import services.ElasticsearchPlugin
import java.io.File
import org.bson.types.ObjectId
import java.util.Date
import java.util.TimeZone
import services.ExtractorMessage
import securesocial.core.SecureSocial
import java.text.SimpleDateFormat
import views.html.defaultpages.badRequest
import com.mongodb.casbah.commons.MongoDBObject
import models.SectionDAO
import play.api.mvc.Flash
import scala.collection.immutable.Nil
import models._
import fileutils.FilesUtils
import api.WithPermission
import api.Permission

/**
 * A dataset is a collection of files and streams.
 * 
 * @author Luigi Marini
 *
 */

object ActivityFound extends Exception { }

object Datasets extends SecuredController {

   
  /**
   * New dataset form.
   */
  val datasetForm = Form(
    mapping(
      "name" -> nonEmptyText,
      "description" -> nonEmptyText
    )
    ((name, description) => Dataset(name = name, description = description, created = new Date, author=null))
    ((dataset: Dataset) => Some((dataset.name, dataset.description)))
   )
   
  def newDataset()  = SecuredAction(authorization=WithPermission(Permission.CreateDatasets)) { implicit request =>
    implicit val user = request.user
  	Ok(views.html.newDataset(datasetForm)).flashing("error"->"Please select a file") 
  }
   
  /**
   * List datasets.
   */
  def list(when: String, date: String, limit: Int) = SecuredAction(authorization=WithPermission(Permission.ListDatasets)) { implicit request =>
    implicit val user = request.user
    var direction = "b"
    if (when != "") direction = when
    val formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    var prev, next = ""
    var datasets = List.empty[models.Dataset]
    if (direction == "b") {
	    datasets = Services.datasets.listDatasetsBefore(date, limit)
    } else if (direction == "a") {
    	datasets = Services.datasets.listDatasetsAfter(date, limit)
    } else {
      badRequest
    }
    // latest object
    val latest = Dataset.find(MongoDBObject()).sort(MongoDBObject("created" -> -1)).limit(1).toList
    // first object
    val first = Dataset.find(MongoDBObject()).sort(MongoDBObject("created" -> 1)).limit(1).toList
    var firstPage = false
    var lastPage = false
    if (latest.size == 1) {
    	firstPage = datasets.exists(_.id == latest(0).id)
    	lastPage = datasets.exists(_.id == first(0).id)
    	Logger.debug("latest " + latest(0).id + " first page " + firstPage )
    	Logger.debug("first " + first(0).id + " last page " + lastPage )
    }
    if (datasets.size > 0) {  
      if (date != "" && !firstPage) { // show prev button
    	prev = formatter.format(datasets.head.created)
      }
      if (!lastPage) { // show next button
    	next = formatter.format(datasets.last.created)
      }
    }
    Ok(views.html.datasetList(datasets, prev, next, limit))
  }
  
 
 
  /**
   * Dataset.
   */
  def dataset(id: String) = SecuredAction(authorization=WithPermission(Permission.ShowDataset)) { implicit request =>
    implicit val user = request.user    
    Previewers.findPreviewers.foreach(p => Logger.info("Previewer found " + p.id))
    Services.datasets.get(id)  match {
      case Some(dataset) => {
        val files = dataset.files map { f =>{
        		FileDAO.get(f.id.toString).get
        	}
        }
        
        //Search whether dataset is currently being processed by extractor(s)
        var isActivity = false
        try{
        	for(f <- files){
        		Extraction.findMostRecentByFileId(f.id) match{
        		case Some(mostRecent) => {
        			mostRecent.status match{
        			case "DONE." => 
        			case _ => { 
        				isActivity = true
        				throw ActivityFound
        			  }  
        			}
        		}
        		case None =>       
        		}
        	}
        }catch{
          case ActivityFound =>
        }
        
        
        val datasetWithFiles = dataset.copy(files = files)
        val previewers = Previewers.findPreviewers
        val previewslist = for(f <- datasetWithFiles.files) yield {
          val pvf = for(p <- previewers ; pv <- f.previews; if (p.contentType.contains(pv.contentType))) yield { 
            (pv.id.toString, p.id, p.path, p.main, api.routes.Previews.download(pv.id.toString).toString, pv.contentType, pv.length)
          }        
          if (pvf.length > 0) {
            (f -> pvf)
          } else {
  	        val ff = for(p <- previewers ; if (p.contentType.contains(f.contentType))) yield {
  	          (f.id.toString, p.id, p.path, p.main, routes.Files.download(f.id.toString).toString, f.contentType, f.length)
  	        }
  	        (f -> ff)
          }
        }
        val previews = Map(previewslist:_*)
        val metadata = Dataset.getMetadata(id)
        Logger.debug("Metadata: " + metadata)
        for (md <- metadata) {
          Logger.debug(md.toString)
        }       
        val userMetadata = Dataset.getUserMetadata(id)
        Logger.debug("User metadata: " + userMetadata.toString)
        
        val collectionsOutside = Collection.listOutsideDataset(id).sortBy(_.name)
        val collectionsInside = Collection.listInsideDataset(id).sortBy(_.name)
        
        var comments = Comment.findCommentsByDatasetId(id)
        files.map { file =>
          comments ++= Comment.findCommentsByFileId(file.id.toString())
          SectionDAO.findByFileId(file.id).map { section =>
            comments ++= Comment.findCommentsBySectionId(section.id.toString())
          } 
        }
        comments = comments.sortBy(_.posted)
        
        Ok(views.html.dataset(datasetWithFiles, comments, previews, metadata, userMetadata, isActivity, collectionsOutside, collectionsInside))
      }
      case None => {Logger.error("Error getting dataset" + id); InternalServerError}
    }
  }
  
  /**
   * Dataset by section.
   */
  def datasetBySection(section_id: String) = SecuredAction(authorization=WithPermission(Permission.ShowDataset)) { request =>
    SectionDAO.findOneById(new ObjectId(section_id)) match {
      case Some(section) => {
        Dataset.findOneByFileId(section.file_id) match {
          case Some(dataset) => Redirect(routes.Datasets.dataset(dataset.id.toString))
          case None => InternalServerError("Dataset not found")
        }   
      }
      case None =>  InternalServerError("Section not found")
    }
  }
  
  /**
   * TODO where is this used?
  def upload = Action(parse.temporaryFile) { request =>
    request.body.moveTo(new File("/tmp/picture"))
    Ok("File uploaded")
  }
   */

  /**
   * Upload file.
   */
  def submit() = SecuredAction(parse.multipartFormData, authorization=WithPermission(Permission.CreateDatasets)) { implicit request =>
    implicit val user = request.user
    
    user match {
      case Some(identity) => {
        datasetForm.bindFromRequest.fold(
          errors => BadRequest(views.html.newDataset(errors)),
	      dataset => {
	           request.body.file("file").map { f =>
		        Logger.debug("Uploading file " + f.filename)
		        
		        // store file
		        Logger.info("Adding file" + identity)
			    val file = Services.files.save(new FileInputStream(f.ref.file), f.filename, f.contentType, identity)
			    Logger.debug("Uploaded file id is " + file.get.id)
			    Logger.debug("Uploaded file type is " + f.contentType)
			    
			    val uploadedFile = f
			    file match {
			      case Some(f) => {			        
			        var fileType = f.contentType
			        if(fileType.contains("/zip") || fileType.contains("/x-zip") || f.filename.endsWith(".zip")){
			          fileType = FilesUtils.getMainFileTypeOfZipFile(uploadedFile.ref.file, f.filename, "dataset")			          
			          if(fileType.startsWith("ERROR: ")){
			             Logger.error(fileType.substring(7))
			             InternalServerError(fileType.substring(7))
			          }			          
			        }			        			        
			    	// TODO RK need to replace unknown with the server name
			    	val key = "unknown." + "file."+ fileType.replace(".", "_").replace("/", ".")
//			        val key = "unknown." + "file."+ "application.x-ptm"
			    	
	                // TODO RK : need figure out if we can use https
	                val host = "http://" + request.host + request.path.replaceAll("dataset/submit$", "")
	                val id = f.id.toString
      
	                //If uploaded file contains zipped files to be unzipped and added to the dataset, wait until the dataset is saved before sending extractor messages to unzip
	                //and return the files
	                if(!fileType.equals("multi/files-zipped")){
				        current.plugin[RabbitmqPlugin].foreach{_.extract(ExtractorMessage(id, id, host, key, Map.empty, f.length.toString, "", ""))}
				        current.plugin[ElasticsearchPlugin].foreach{_.index("data", "file", id, List(("filename",f.filename), ("contentType", f.contentType)))}
			        }
			        
			        // add file to dataset 
			        val dt = dataset.copy(files = List(f), author=identity)
			        // TODO create a service instead of calling salat directly
		            Dataset.save(dt)
		            
		            if(fileType.equals("multi/files-zipped")){
				        current.plugin[RabbitmqPlugin].foreach{_.extract(ExtractorMessage(id, id, host, key, Map.empty, f.length.toString, dt.id.toString, ""))}
				        current.plugin[ElasticsearchPlugin].foreach{_.index("data", "file", id, List(("filename",f.filename), ("contentType", f.contentType)))}
			        }
		            
		            // index dataset
		            current.plugin[ElasticsearchPlugin].foreach{_.index("data", "dataset", id, 
		                List(("name",dt.name), ("description", dt.description)))}
           
		            
			    	// TODO RK need to replace unknown with the server name and dataset type		            
 			    	val dtkey = "unknown." + "dataset."+ "unknown"
			        current.plugin[RabbitmqPlugin].foreach{_.extract(ExtractorMessage(dt.id.toString, dt.id.toString, host, dtkey, Map.empty, "0", dt.id.toString, ""))}
		            // redirect to file page
		            Redirect(routes.Datasets.dataset(dt.id.toString))
//		            Ok(views.html.dataset(dt, Previewers.searchFileSystem))
			      }
			      
			      case None => {
			        Logger.error("Could not retrieve file that was just saved.")
			        // TODO create a service instead of calling salat directly
			        val dt = dataset.copy(author=identity)
		            Dataset.save(dt)
		            // redirect to file page
		            Redirect(routes.Datasets.dataset(dt.id.toString))
//		            Ok(views.html.dataset(dt, Previewers.searchFileSystem))
			      }
			    }   
	        }.getOrElse{
	          Redirect(routes.Datasets.newDataset()).flashing("error"->"Please select a file")
	        }
		  }
		)
      }
      case None => Redirect(routes.Datasets.list()).flashing("error" -> "You are not authorized to create new datasets.")
    }
  }
  
  def metadataSearch()  = SecuredAction(authorization=WithPermission(Permission.SearchDatasets)) { implicit request =>
    implicit val user = request.user
  	Ok(views.html.metadataSearch()) 
  }
  
  
  
}
