package io.vamp.core.rest_api

import io.vamp.common.notification.NotificationErrorException
import io.vamp.core.model.serialization.{PrettyJson, SerializationFormat}
import org.json4s.native.Serialization._
import org.yaml.snakeyaml.DumperOptions.FlowStyle
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.Tag
import shapeless.HNil
import spray.http.CacheDirectives.`no-store`
import spray.http.HttpHeaders.{RawHeader, `Cache-Control`, `Content-Type`}
import spray.http.MediaTypes._
import spray.http.{HttpEntity, MediaRange, MediaType}
import spray.httpx.marshalling.{Marshaller, ToResponseMarshaller}
import spray.routing._

trait RestApiContentTypes {
  val `application/x-yaml` = register(MediaType.custom(mainType = "application", subType = "x-yaml", compressible = true, binary = true, fileExtensions = Seq("yaml")))
}

trait RestApiBase extends HttpServiceBase with RestApiMarshaller with RestApiContentTypes {

  protected def noCachingAllowed = respondWithHeaders(`Cache-Control`(`no-store`), RawHeader("Pragma", "no-cache"))

  protected def allowXhrFromOtherHosts = respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*"))

  protected def accept(mr: MediaRange*): Directive0 = headerValueByName("Accept").flatMap {
    case actual if actual.split(",").map(_.trim).exists(v => v.startsWith("*/*") || mr.exists(_.value == v)) => pass
    case actual => reject(MalformedHeaderRejection("Accept", s"Only the following media types are supported: ${mr.mkString(", ")}, but not: $actual"))
  }

  protected def contentTypeOnly(mt: MediaType*): Directive0 = extract(_.request.headers).flatMap[HNil] {
    case headers if mt.exists(t => headers.contains(`Content-Type`(t))) => pass
    case _ => reject(MalformedHeaderRejection("Content-Type", s"Only the following media types are supported: ${mt.mkString(", ")}"))
  } & cancelAllRejections(ofType[MalformedHeaderRejection])

  protected def contentTypeForModification = contentTypeOnly(`application/json`, `application/x-yaml`)

  override def delete: Directive0 = super.delete & contentTypeForModification

  override def put: Directive0 = super.put & contentTypeForModification

  override def post: Directive0 = super.post & contentTypeForModification
}

trait RestApiMarshaller {
  this: RestApiContentTypes =>

  implicit def marshaller: ToResponseMarshaller[Any] = ToResponseMarshaller.oneOf(`application/json`, `application/x-yaml`)(jsonMarshaller, yamlMarshaller)

  def jsonMarshaller: Marshaller[Any] = Marshaller.of[Any](`application/json`) { (value, contentType, ctx) => ctx.marshalTo(HttpEntity(contentType, toJson(bodyFor(value)))) }

  def yamlMarshaller: Marshaller[Any] = Marshaller.of[Any](`application/x-yaml`) { (value, contentType, ctx) =>
    val yaml = new Yaml()
    val body = bodyFor(value)
    val response = yaml.dumpAs(yaml.load(toJson(body)), if (body.isInstanceOf[List[_]]) Tag.SEQ else Tag.MAP, FlowStyle.BLOCK)
    ctx.marshalTo(HttpEntity(contentType, response))
  }

  def bodyFor(any: Any) = any match {
    case (_1, _2) => _2
    case v => v
  }

  def toJson(any: Any) = {
    implicit val formats = SerializationFormat.default
    any match {
      case notification: NotificationErrorException => throw notification
      case exception: Exception => throw new RuntimeException(exception)
      case value: PrettyJson => writePretty(value)
      case value: AnyRef => write(value)
      case value => write(value.toString)
    }
  }
}
