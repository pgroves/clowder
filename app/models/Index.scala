/*
 * Exact copy of existing IndexList class, this name of the class
 *is more appropriate since it holds information about just one index, not a list.
 * 
 * TODO: Check if IndexList is used anywhere in the project, delete it.
 */

package models
import play.api.libs.json.JsValue
import play.api.libs.json.Reads
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsResult

object Index {

/*import play.api.libs.json._
import play.api.libs.functional.syntax._

implicit val personReads: Reads[Person] = (
  (__ \ "name").read[String]
    and (__ \ "age").read[Int]
  )(Person)

val futureResult: Future[JsResult[Person]] = WS.url(url).get().map {
  response => (response.json \ "person").validate[Person]
}*/


  //case class IndexList( val indexID :String, val MIMEtype :String)
  case class Index( 
      val id :String, 
      val MIMEtype :String, 
      val extractorID:String, 
      val measureID:String, 
      val indexerType:String)
  
  
   implicit object Index extends Reads[Index] {
    def reads(json: JsValue) ={
     
      val maybeID:String = (json \"indexID").as[String]
      val maybeType:String=(json\"MIMEtype").as[String]
      val exType:String=(json\"Extractor").as[String]
      val meType:String=(json\"Measure").as[String]
      val indxrType:String=(json\"Indexer").as[String]
      JsSuccess(Index(maybeID,maybeType,exType,meType,indxrType) )
         
    }

    }
}
