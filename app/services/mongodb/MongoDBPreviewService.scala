package services.mongodb

import java.io.{BufferedReader, InputStream, InputStreamReader}

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.WriteConcern
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.TypeImports.ObjectId
import javax.inject.{Inject, Singleton}
import services._
import models.{Preview, services, _}
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue}
import salat.dao.{ModelCompanion, SalatDAO}
import services.mongodb.MongoContext.context
import util.FileUtils

import scala.collection.JavaConverters._

/**
 * Use MongoDB to store previews
 */
@Singleton
class MongoDBPreviewService @Inject()(files: FileService, tiles: TileService, storage: ByteStorageService) extends PreviewService {


  /**
   * Count all files
   */
  def count(): Long = {
    PreviewDAO.count(MongoDBObject())
  }

  /**
   * List all thumbnail files.
   */
  def listPreviews(): List[Preview] = {
    (for (preview <- PreviewDAO.find(MongoDBObject())) yield preview).toList
  }

  def get(previewId: UUID): Option[Preview] = {
    PreviewDAO.findOneById(new ObjectId(previewId.stringify))
  }

  def get(previewIds: List[UUID]): DBResult[Preview] = {
    val objectIdList = previewIds.map(id => {
      new ObjectId(id.stringify)
    })
    val query = MongoDBObject("_id" -> MongoDBObject("$in" -> objectIdList))

    val found = PreviewDAO.find(query).toList
    val notFound = previewIds.diff(found.map(_.id))
    if (notFound.length > 0)
      Logger.error("Not all file IDs found for bulk get request")
    return DBResult(found, notFound)
  }

  def findByFileId(id: UUID): List[Preview] = {
    PreviewDAO.find(MongoDBObject("file_id" -> new ObjectId(id.stringify))).toList
  }

  def findBySectionId(id: UUID): List[Preview] = {
    PreviewDAO.find(MongoDBObject("section_id" -> new ObjectId(id.stringify))).toList
  }

  def findByDatasetId(id: UUID): List[Preview] = {
    PreviewDAO.find(MongoDBObject("dataset_id" -> new ObjectId(id.stringify))).toList
  }

  def findByCollectionId(id: UUID): List[Preview] = {
    PreviewDAO.find(MongoDBObject("collection_id" -> new ObjectId(id.stringify))).toList
  }

  /**
   * Save blob.
   */
  def save(inputStream: InputStream, filename: String, contentLength: Long, contentType: Option[String]): String = {
    ByteStorageService.save(inputStream, PreviewDAO.COLLECTION, contentLength) match {
      case Some(x) => {
        val preview = Preview(UUID.generate(), x._1, x._2, None, None, None, None, Some(filename), FileUtils.getContentType(filename, contentType), None, None, x._3)
        PreviewDAO.save(preview)
        preview.id.stringify
      }
      case None => ""
    }
  }

  /**
   * Get blob.
   */
  def getBlob(id: UUID): Option[(InputStream, String, String, Long)] = {
    get(id).flatMap { x =>
      ByteStorageService.load(x.loader, x.loader_id, PreviewDAO.COLLECTION).map((_, x.filename.getOrElse(""), x.contentType, x.length))
    }
  }

  def remove(id: UUID): Unit = {
    get(id).foreach{ x=>
      ByteStorageService.delete(x.loader, x.loader_id, PreviewDAO.COLLECTION)
      PreviewDAO.remove(x)
    }
  }


  def removePreview(p: Preview) {
    for (tile <- tiles.get(p.id)) {
      tiles.remove(tile.id)
    }
    if (!p.filename.isEmpty)
    // for oni previews, read the ONI frame references from the preview file and remove them
      if (p.filename.get.endsWith(".oniv")) {
        val theFile = getBlob(p.id)
        val frameRefReader = new BufferedReader(new InputStreamReader(theFile.get._1))
        var fileData = new StringBuilder()
        var currLine = frameRefReader.readLine()
        while (currLine != null) {
          fileData.append(currLine)
          currLine = frameRefReader.readLine()
        }
        frameRefReader.close()
        val frames = fileData.toString().split(",", -1)
        var i = 0
        for (i <- 0 to frames.length - 2) {
          PreviewDAO.remove(MongoDBObject("_id" -> new ObjectId(frames(i))))
        }
        //same for PTM file map references
      } else if (p.filename.get.endsWith(".ptmmaps")) {
        val theFile = getBlob(p.id)
        val frameRefReader = new BufferedReader(new InputStreamReader(theFile.get._1))
        var currLine = frameRefReader.readLine()
        while (currLine != null) {
          if (!currLine.equals(""))
            PreviewDAO.remove(MongoDBObject("_id" -> new ObjectId(currLine.substring(currLine.indexOf(": ") + 2))))
          currLine = frameRefReader.readLine()
        }
        frameRefReader.close()
      }

    // finally delete the actual file
    ByteStorageService.delete(p.loader, p.loader_id, PreviewDAO.COLLECTION)
    PreviewDAO.remove(p)
  }

  def attachToFile(previewId: UUID, fileId: UUID, extractorId: Option[String], json: JsValue) {
    json match {
      case JsObject(fields) => {
        Logger.debug("attachToFile: extractorId is '" + extractorId.toString + "'.")
        // "extractor_id" is stored at the top level of "Preview".  Remove it from the "metadata" field to avoid dup.
        val metadata = (fields.toMap - "extractor_id").flatMap(tuple => MongoDBObject(tuple._1 -> tuple._2.as[String]))
        PreviewDAO.dao.collection.update(MongoDBObject("_id" -> new ObjectId(previewId.stringify)),
          $set("metadata" -> metadata, "file_id" -> new ObjectId(fileId.stringify), "extractor_id" -> extractorId),
          false, false, WriteConcern.Safe)
        Logger.debug("Updating previews.files " + previewId + " with " + metadata)
      }
      case _ => Logger.error(s"Received something else: $json")
    }
  }

  def attachToCollection(previewId: UUID, collectionId: UUID, previewType: String, extractorId: Option[String], json: JsValue) {
    json match {
      case JsObject(fields) => {
        Logger.debug("attachToCollection: extractorId is '" + extractorId.toString + "'.")
        // "extractor_id" is stored at the top level of "Preview".  Remove it from the "metadata" field to avoid dup.
        val metadata = (fields.toMap - "extractor_id").flatMap(tuple => MongoDBObject(tuple._1 -> tuple._2.as[String]))
        PreviewDAO.dao.collection.update(MongoDBObject("_id" -> new ObjectId(previewId.stringify)),
          $set("metadata" -> metadata, "collection_id" -> new ObjectId(collectionId.stringify),
            "extractor_id" -> extractorId, "preview_type" -> previewType),
          false, false, WriteConcern.Safe)
        Logger.debug("Updating previews.collections " + previewId + " with " + metadata)
      }
      case _ => Logger.error(s"Received something else: $json")
    }
  }

  def updateMetadata(previewId: UUID, json: JsValue) {
    json match {
      case JsObject(fields) => {
        val metadata = fields.toMap.flatMap(tuple => MongoDBObject(tuple._1 -> tuple._2.as[String]))
        // TODO figure out a way to do it all together
        // aways update metadata
        PreviewDAO.dao.collection.update(
          MongoDBObject("_id" -> new ObjectId(previewId.stringify)),
          $set("metadata" -> metadata),
          false, false, WriteConcern.Safe)
        // update section_id if it exists
        if (metadata.contains("section_id")) {
          val section_id = metadata("section_id").asInstanceOf[String]
          Logger.debug("Updating previews.files " + previewId + " with section_id=" + section_id)
          PreviewDAO.dao.collection.update(
            MongoDBObject("_id" -> new ObjectId(previewId.stringify)),
            $set("section_id" -> new ObjectId(section_id)),
            false, false, WriteConcern.Safe)
        }
        // update file_id if it exists
        if (metadata.contains("file_id")) {
          val file_id = metadata("file_id").asInstanceOf[String]
          Logger.debug("Updating previews.files " + previewId + " with file_id=" + file_id)
          PreviewDAO.dao.collection.update(
            MongoDBObject("_id" -> new ObjectId(previewId.stringify)),
            $set("file_id" -> new ObjectId(file_id)),
            false, false, WriteConcern.Safe)
        }
	      Logger.debug("Updating previews.files " + previewId + " with " + metadata)
      }
      case _ => Logger.error("Expected a JSObject")
    }
  }

  def setTitle(previewId: UUID, title: String) {
    PreviewDAO.dao.collection.update(
          MongoDBObject("_id" -> new ObjectId(previewId.stringify)),
          $set("title" -> title),
          upsert=false, multi=false, WriteConcern.Safe)
  }
  
  /**
   * Get metadata from the mongo db as a map. 
   * 
   */
   def getMetadata(id: UUID): scala.collection.immutable.Map[String,Any] = {
    PreviewDAO.dao.collection.findOneByID(new ObjectId(id.stringify)) match {
      case None => new scala.collection.immutable.HashMap[String,Any]
      case Some(x) => {
        val returnedMetadata = x.getAs[DBObject]("metadata").get.toMap.asScala.asInstanceOf[scala.collection.mutable.Map[String,Any]].toMap
        returnedMetadata
      }
    }
  }
  
    def getExtractorId(id: UUID):String = {     
      val extractor_id = getMetadata(id)("extractor_id").toString    
      extractor_id
   }
    
}

object PreviewDAO extends ModelCompanion[Preview, ObjectId] {
  val COLLECTION = "previews"
  val mongos: MongoStartup = DI.injector.getInstance(classOf[MongoStartup])
  val dao = new SalatDAO[Preview, ObjectId](collection = mongos.collection(COLLECTION)) {}
}

