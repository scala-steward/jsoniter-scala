package com.github.plokhotnyuk.jsoniter_scala.benchmark

import com.github.plokhotnyuk.jsoniter_scala.benchmark.SuitEnum.SuitEnum
import zio.json.JsonDecoder.JsonError
import zio.json.internal.Lexer.error
import zio.json.internal.{Lexer, RetractReader, SafeNumbers, StringMatrix, UnsafeNumbers, Write}
import zio.json.{DeriveJsonCodec, ExplicitEmptyCollections, JsonCodec, JsonCodecConfiguration, JsonDecoder, JsonEncoder}
import java.util.Base64

object ZioJsonCodecs {
  implicit val config: JsonCodecConfiguration = JsonCodecConfiguration(
    explicitEmptyCollections = ExplicitEmptyCollections(encoding = false, decoding = false),
    enumValuesAsStrings = true
  )
  implicit val geoJsonC3c: JsonCodec[GeoJSON.GeoJSON] = {
    implicit val c1: JsonCodec[GeoJSON.SimpleGeometry] = DeriveJsonCodec.gen
    implicit val c2: JsonCodec[GeoJSON.Geometry] = DeriveJsonCodec.gen
    implicit val c3: JsonCodec[GeoJSON.SimpleGeoJSON] = DeriveJsonCodec.gen
    DeriveJsonCodec.gen
  }
  implicit lazy val nestedStructsC3c: JsonCodec[NestedStructs] = DeriveJsonCodec.gen
  implicit val openRTBBidRequestC3c: JsonCodec[OpenRTB.BidRequest] = {
    def genOpenRTBBidRequestC3c1: JsonCodec[OpenRTB.BidRequest] = {
      implicit val c1: JsonCodec[OpenRTB.Segment] = DeriveJsonCodec.gen
      implicit val c2: JsonCodec[OpenRTB.Format] = DeriveJsonCodec.gen
      implicit val c3: JsonCodec[OpenRTB.Deal] = DeriveJsonCodec.gen
      implicit val c4: JsonCodec[OpenRTB.Metric] = DeriveJsonCodec.gen
      implicit val c5: JsonCodec[OpenRTB.Banner] = DeriveJsonCodec.gen
      implicit val c6: JsonCodec[OpenRTB.Audio] = DeriveJsonCodec.gen
      implicit val c7: JsonCodec[OpenRTB.Video] = DeriveJsonCodec.gen

      def genOpenRTBBidRequestC3c2: JsonCodec[OpenRTB.BidRequest] = {
        implicit val c8: JsonCodec[OpenRTB.Native] = DeriveJsonCodec.gen
        implicit val c9: JsonCodec[OpenRTB.Pmp] = DeriveJsonCodec.gen
        implicit val c10: JsonCodec[OpenRTB.Producer] = DeriveJsonCodec.gen
        implicit val c11: JsonCodec[OpenRTB.Data] = DeriveJsonCodec.gen
        implicit val c12: JsonCodec[OpenRTB.Content] = DeriveJsonCodec.gen
        implicit val c13: JsonCodec[OpenRTB.Publisher] = DeriveJsonCodec.gen
        implicit val c14: JsonCodec[OpenRTB.Geo] = DeriveJsonCodec.gen

        def genOpenRTBBidRequestC3c3: JsonCodec[OpenRTB.BidRequest] = {
          implicit val c15: JsonCodec[OpenRTB.Imp] = DeriveJsonCodec.gen
          implicit val c16: JsonCodec[OpenRTB.Site] = DeriveJsonCodec.gen
          implicit val c17: JsonCodec[OpenRTB.App] = DeriveJsonCodec.gen
          implicit val c18: JsonCodec[OpenRTB.Device] = DeriveJsonCodec.gen
          implicit val c19: JsonCodec[OpenRTB.User] = DeriveJsonCodec.gen
          implicit val c20: JsonCodec[OpenRTB.Source] = DeriveJsonCodec.gen
          implicit val c21: JsonCodec[OpenRTB.Reqs] = DeriveJsonCodec.gen
          DeriveJsonCodec.gen
        }

        genOpenRTBBidRequestC3c3
      }

      genOpenRTBBidRequestC3c2
    }

    genOpenRTBBidRequestC3c1
  }
  implicit val twitterAPIC3c: JsonCodec[TwitterAPI.Tweet] = {
    def genTwitterAPIC3c: JsonCodec[TwitterAPI.Tweet] = {
      implicit val c1: JsonCodec[TwitterAPI.UserMentions] = DeriveJsonCodec.gen
      implicit val c2: JsonCodec[TwitterAPI.Urls] = DeriveJsonCodec.gen
      implicit val c3: JsonCodec[TwitterAPI.Entities] = DeriveJsonCodec.gen
      implicit val c4: JsonCodec[TwitterAPI.Url] = DeriveJsonCodec.gen
      implicit val c5: JsonCodec[TwitterAPI.UserEntities] = DeriveJsonCodec.gen
      implicit val c6: JsonCodec[TwitterAPI.User] = DeriveJsonCodec.gen
      implicit val c7: JsonCodec[TwitterAPI.RetweetedStatus] = DeriveJsonCodec.gen
      DeriveJsonCodec.gen
    }

    genTwitterAPIC3c
  }
  val base64C3c: JsonCodec[Array[Byte]] = new JsonCodec[Array[Byte]](
    (a: Array[Byte], _: Option[Int], out: Write) => {
      out.write('"')
      out.write(Base64.getEncoder.encodeToString(a))
      out.write('"')
    },
    (trace: List[JsonError], in: RetractReader) => Base64.getDecoder.decode(Lexer.string(trace, in).toString))
  val bigDecimalC3c: JsonCodec[BigDecimal] = new JsonCodec[BigDecimal](
    (a: BigDecimal, _: Option[Int], out: Write) => SafeNumbers.write(a.bigDecimal, out),
    (trace: List[JsonError], in: RetractReader) => {
      try {
        val i = UnsafeNumbers.bigDecimal_(in, false, Int.MaxValue)
        in.retract()
        i
      } catch {
        case UnsafeNumbers.UnsafeNumber => error("expected number", trace)
      }
    })
  val bigIntC3c: JsonCodec[BigInt] = new JsonCodec[BigInt](
    (a: BigInt, _: Option[Int], out: Write) => {
      if (a.isValidLong) SafeNumbers.write(a.longValue, out)
      else SafeNumbers.write(a.bigInteger, out)
    },
    (trace: List[JsonError], in: RetractReader) => {
      try {
        val i = BigInt(UnsafeNumbers.bigInteger_(in, false, Int.MaxValue))
        in.retract()
        i
      } catch {
        case UnsafeNumbers.UnsafeNumber => error("expected number", trace)
      }
    })
  implicit val extractFieldsC3c: JsonCodec[ExtractFields] = DeriveJsonCodec.gen
  implicit val gitHubActionsAPIC3c: JsonCodec[GitHubActionsAPI.Response] = {
    implicit val e1: JsonEncoder[Boolean] = (a: Boolean, _: Option[Int], out: Write) =>
      out.write(if (a) "\"true\"" else "\"false\"")
    implicit val d1: JsonDecoder[Boolean] = (trace: List[JsonError], in: RetractReader) => {
      Lexer.char(trace, in, '"')
      val x = Lexer.boolean(trace, in)
      Lexer.char(trace, in, '"')
      x
    }
    implicit val c2: JsonCodec[GitHubActionsAPI.Artifact] = DeriveJsonCodec.gen
    DeriveJsonCodec.gen
  }
  implicit val googleMapsAPIC3c: JsonCodec[GoogleMapsAPI.DistanceMatrix] = {
    implicit val c1: JsonCodec[GoogleMapsAPI.Value] = DeriveJsonCodec.gen
    implicit val c2: JsonCodec[GoogleMapsAPI.Elements] = DeriveJsonCodec.gen
    implicit val c3: JsonCodec[GoogleMapsAPI.Rows] = DeriveJsonCodec.gen
    DeriveJsonCodec.gen
  }
  implicit val missingRequiredFieldsC3c: JsonCodec[MissingRequiredFields] = DeriveJsonCodec.gen
  implicit val enumADTsC3c: JsonCodec[SuitADT] = DeriveJsonCodec.gen
  implicit val enumsC3c: JsonCodec[SuitEnum] = new JsonCodec(
    (a: SuitEnum, _: Option[Int], out: Write) => {
      out.write('"')
      out.write(a.toString)
      out.write('"')
    }, {
      val values = SuitEnum.values.toArray
      val matrix = new StringMatrix(values.map(_.toString))
      (trace: List[JsonError], in: RetractReader) => {
        val idx = Lexer.enumeration(trace, in, matrix)
        if (idx < 0) Lexer.error("SuitEnum", trace)
        values(idx)
      }
    })
}