package com.github.plokhotnyuk.jsoniter_scala.benchmark

import org.json4s._
import org.json4s.ext.{EnumNameSerializer, UUIDSerializer}
import java.time._
import java.util.Base64
import scala.reflect.ClassTag
import com.fasterxml.jackson.core._
import com.fasterxml.jackson.core.util._
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind._
import org.json4s.jackson.Json4sScalaModule

object CommonJson4sFormats {
  implicit val commonFormats: Formats = DefaultFormats +
    StringifiedFormats.stringified[Char](x => if (x.length == 1) x.charAt(0) else sys.error("char")) +
    UUIDSerializer +
    new EnumNameSerializer(SuitEnum) +
    StringifiedFormats.stringified[Suit](Suit.valueOf) +
    StringifiedFormats.stringified[SuitADT] {
      val m = Map(
        "Hearts" -> Hearts,
        "Spades" -> Spades,
        "Diamonds" -> Diamonds,
        "Clubs" -> Clubs)
      x => m.getOrElse(x, sys.error("SuitADT"))
    }
}

object BigDecimalJson4sFormat {
  implicit val bigDecimalFormats: Formats = DefaultFormats.withBigDecimal.withBigInt
}

object JavaTimeJson4sFormats {
  implicit val javaTimeFormats: Formats = DefaultFormats +
    StringifiedFormats.stringified[Duration](Duration.parse) +
    StringifiedFormats.stringified[Instant](Instant.parse) +
    StringifiedFormats.stringified[LocalDate](LocalDate.parse) +
    StringifiedFormats.stringified[LocalDateTime](LocalDateTime.parse) +
    StringifiedFormats.stringified[LocalTime](LocalTime.parse) +
    StringifiedFormats.stringified[MonthDay](MonthDay.parse) +
    StringifiedFormats.stringified[OffsetDateTime](OffsetDateTime.parse) +
    StringifiedFormats.stringified[OffsetTime](OffsetTime.parse) +
    StringifiedFormats.stringified[Period](Period.parse) +
    StringifiedFormats.stringified[Year](Year.parse) +
    StringifiedFormats.stringified[YearMonth](YearMonth.parse) +
    StringifiedFormats.stringified[ZonedDateTime](ZonedDateTime.parse) +
    StringifiedFormats.stringified[ZoneId](ZoneId.of) +
    StringifiedFormats.stringified[ZoneOffset](ZoneOffset.of)
}

object GeoJsonJson4sFormats {
  implicit val geoJsonFormats: Formats = new DefaultFormats {
    override val typeHints: TypeHints = new SimpleTypeHints(List(
      classOf[GeoJSON.Point], classOf[GeoJSON.MultiPoint], classOf[GeoJSON.LineString],
      classOf[GeoJSON.MultiLineString], classOf[GeoJSON.Polygon], classOf[GeoJSON.MultiPolygon],
      classOf[GeoJSON.GeometryCollection], classOf[GeoJSON.Feature], classOf[GeoJSON.FeatureCollection]))
  } +
    new CustomSerializer[(Double, Double)](_ => ({
      case JArray(JDouble(x) :: JDouble(y) :: Nil) => new Tuple2[Double, Double](x, y)
    }, {
      case (x: Double, y: Double) => new JArray(new JDouble(x) :: new JDouble(y) :: Nil)
    }))
}

object GitHubActionsAPIJson4sFormats {
  implicit val gitHubActionsAPIFormats: Formats = DefaultFormats +
    StringifiedFormats.stringified[Instant](Instant.parse) +
    StringifiedFormats.stringified[Boolean](java.lang.Boolean.parseBoolean)
}

object Base64Json4sFormats {
  implicit val base64Formats: Formats = DefaultFormats +
    StringifiedFormats.stringified[Array[Byte]](Base64.getDecoder.decode, Base64.getEncoder.encodeToString)
}

object EscapeUnicodeJson4sFormats {
  implicit val escapeUnicodeFormats: Formats = DefaultFormats.withEscapeUnicode
}

object StringifiedFormats {
  def stringified[A: ClassTag](f: String => A, g: A => String = (x: A) => x.toString): CustomSerializer[A] =
    new CustomSerializer[A](_ => ({
      case js: JString => f(js.s)
    }, {
      case x: A => new JString(g(x))
    }))
}

class SimpleTypeHints(override val hints: List[Class[_]],
                      override val typeHintFieldName: String = "type") extends TypeHints {
  override def hintFor(clazz: Class[_]): Option[String] = new Some(clazz.getSimpleName)

  override def classFor(hint: String, parent: Class[_]): Option[Class[_]] = hints.collectFirst {
    case clazz if clazz.getSimpleName == hint => clazz
  }
}

object Json4sJacksonMappers {
  private[this] def mapper(indentOutput: Boolean = false, escapeNonAscii: Boolean = false,
                           useBigNumber: Boolean = false): ObjectMapper = {
    val jsonFactory = new JsonFactoryBuilder()
      .configure(JsonFactory.Feature.INTERN_FIELD_NAMES, false)
      .configure(JsonWriteFeature.ESCAPE_NON_ASCII, escapeNonAscii)
      .configure(StreamReadFeature.USE_FAST_DOUBLE_PARSER, true)
      .configure(StreamWriteFeature.USE_FAST_DOUBLE_WRITER, true)
      .configure(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER, true)
      .build()
    new ObjectMapper(jsonFactory)
      .registerModule(new Json4sScalaModule)
      .configure(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS, useBigNumber)
      .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, useBigNumber)
      .configure(SerializationFeature.INDENT_OUTPUT, indentOutput)
      .setDefaultPrettyPrinter {
        val indenter = new DefaultIndenter("  ", "\n")
        new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter)
      }
  }

  val mapper: ObjectMapper = mapper()
  val jValueType: JavaType = mapper.constructType(classOf[JValue])
  val bigNumberMapper: ObjectMapper = mapper(useBigNumber = true)
  val prettyPrintMapper: ObjectMapper = mapper(indentOutput = true)
  val escapeNonAsciiMapper: ObjectMapper = mapper(escapeNonAscii = true)
}
