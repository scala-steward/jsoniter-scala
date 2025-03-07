package com.github.plokhotnyuk.jsoniter_scala.benchmark

import java.math.MathContext
import java.time._
import java.util.UUID
import com.github.plokhotnyuk.jsoniter_scala.benchmark.SuitEnum.SuitEnum
import io.bullet.borer.{AdtEncodingStrategy, Codec, Decoder, Encoder, Reader, Writer}
import io.bullet.borer.Json.DecodingConfig
import io.bullet.borer.derivation.ArrayBasedCodecs
import io.bullet.borer.derivation.MapBasedCodecs._
import io.bullet.borer.encodings.Base16

object BorerJsonEncodersDecoders {
  val decodingConfig: DecodingConfig = DecodingConfig(
    maxNumberMantissaDigits = 200,
    maxNumberAbsExponent = 999,
    readDecimalNumbersOnlyAsNumberStrings = true)
  val (byteArrayEnc: Encoder[Array[Byte]], byteArrayDec: Decoder[Array[Byte]]) =
    (Encoder.forArray[Byte], Decoder.forArray[Byte])
  val (base16Enc: Encoder[Array[Byte]], base16Dec: Decoder[Array[Byte]]) =
    (Encoder.forByteArray(Base16), Decoder.forByteArray(Base16))
  implicit val bigIntDec: Decoder[BigInt] =
    Decoder.forJBigInteger(maxJsonNumberStringLength = 1000000).map(x => new BigInt(x)) /* WARNING: It is an unsafe option for open systems */
  implicit val bigDecimalDec: Decoder[BigDecimal] =
    Decoder.forJBigDecimal(maxJsonNumberStringLength = 1000000).map(x => new BigDecimal(x, MathContext.UNLIMITED)) /* WARNING: It is an unsafe option for open systems */
  implicit val flatAdtEncoding: AdtEncodingStrategy = AdtEncodingStrategy.flat(typeMemberName = "type")
  implicit val Codec(charEnc: Encoder[Char], charDec: Decoder[Char]) = stringCodec(_.charAt(0))
  implicit val Codec(extractFieldsEnc: Encoder[ExtractFields], extractFieldsDec: Decoder[ExtractFields]) =
    deriveCodec[ExtractFields]
  implicit val Codec(geoJsonEnc: Encoder[GeoJSON.GeoJSON], geoJsonDec: Decoder[GeoJSON.GeoJSON]) = {
    implicit val c1: Codec[GeoJSON.SimpleGeometry] = deriveAllCodecs
    implicit val c2: Codec[GeoJSON.Geometry] = deriveAllCodecs
    implicit val c3: Codec[GeoJSON.SimpleGeoJSON] = deriveAllCodecs
    deriveAllCodecs[GeoJSON.GeoJSON]
  }
  implicit val Codec(durationEnc: Encoder[Duration], durationDec: Decoder[Duration]) = stringCodec(Duration.parse)
  implicit val Codec(instantEnc: Encoder[Instant], instantDec: Decoder[Instant]) = stringCodec(Instant.parse)
  implicit val Codec(localDateEnc: Encoder[LocalDate], localDateDec: Decoder[LocalDate]) = stringCodec(LocalDate.parse)
  implicit val Codec(localDateTimeEnc: Encoder[LocalDateTime], localDateTimeDec: Decoder[LocalDateTime]) =
    stringCodec(LocalDateTime.parse)
  implicit val Codec(localTimeEnc: Encoder[LocalTime], localTimeDec: Decoder[LocalTime]) = stringCodec(LocalTime.parse)
  implicit val Codec(monthDayEnc: Encoder[MonthDay], monthDayDec: Decoder[MonthDay]) = stringCodec(MonthDay.parse)
  implicit val Codec(offsetDateTimeEnc: Encoder[OffsetDateTime], offsetDateTimeDec: Decoder[OffsetDateTime]) =
    stringCodec(OffsetDateTime.parse)
  implicit val Codec(offsetTimeEnc: Encoder[OffsetTime], offsetTimeDec: Decoder[OffsetTime]) =
    stringCodec(OffsetTime.parse)
  implicit val Codec(periodEnc: Encoder[Period], periodDec: Decoder[Period]) = stringCodec(Period.parse)
  implicit val Codec(yearMonthEnc: Encoder[YearMonth], yearMonthDec: Decoder[YearMonth]) = stringCodec(YearMonth.parse)
  implicit val Codec(yearEnc: Encoder[Year], yearDec: Decoder[Year]) = stringCodec(Year.parse)
  implicit val Codec(zonedDateTimeEnc: Encoder[ZonedDateTime], zonedDateTimeDec: Decoder[ZonedDateTime]) =
    stringCodec(ZonedDateTime.parse)
  implicit val Codec(zoneIdEnc: Encoder[ZoneId], zoneIdDec: Decoder[ZoneId]) = stringCodec(ZoneId.of)
  implicit val Codec(zoneOffsetEnc: Encoder[ZoneOffset], zoneOffsetDec: Decoder[ZoneOffset]) =
    stringCodec(ZoneOffset.of)
  implicit val Codec(gitHubActionsAPIEnc: Encoder[GitHubActionsAPI.Response], gitHubActionsAPIDec: Decoder[GitHubActionsAPI.Response]) = {
    import Decoder.StringBooleans.booleanDecoder
    import Encoder.StringBooleans.booleanEncoder

    implicit val c1: Codec[GitHubActionsAPI.Artifact] = deriveCodec
    deriveCodec[GitHubActionsAPI.Response]
  }
  implicit val Codec(googleMapsAPIEnc: Encoder[GoogleMapsAPI.DistanceMatrix], googleMapsAPIDec: Decoder[GoogleMapsAPI.DistanceMatrix]) = {
    implicit val c1: Codec[GoogleMapsAPI.Value] = deriveCodec
    implicit val c2: Codec[GoogleMapsAPI.Elements] = deriveCodec
    implicit val c3: Codec[GoogleMapsAPI.Rows] = deriveCodec
    deriveCodec[GoogleMapsAPI.DistanceMatrix]
  }
  implicit val Codec(missingRequiredFieldsEnc: Encoder[MissingRequiredFields], missingRequiredFieldsDec: Decoder[MissingRequiredFields]) =
    deriveCodec[MissingRequiredFields]
  implicit val Codec(nestedStructsEnc: Encoder[NestedStructs], nestedStructsDec: Decoder[NestedStructs]) =
    deriveCodec[NestedStructs]
  implicit val Codec(openRTBBidRequestEnc: Encoder[OpenRTB.BidRequest], openRTBBidRequestDec: Decoder[OpenRTB.BidRequest]) = {
    implicit val c1: Codec[OpenRTB.Segment] = deriveCodec
    implicit val c2: Codec[OpenRTB.Format] = deriveCodec
    implicit val c3: Codec[OpenRTB.Deal] = deriveCodec
    implicit val c4: Codec[OpenRTB.Metric] = deriveCodec
    implicit val c5: Codec[OpenRTB.Banner] = deriveCodec
    implicit val c6: Codec[OpenRTB.Audio] = deriveCodec
    implicit val c7: Codec[OpenRTB.Video] = deriveCodec
    implicit val c8: Codec[OpenRTB.Native] = deriveCodec
    implicit val c9: Codec[OpenRTB.Pmp] = deriveCodec
    implicit val c10: Codec[OpenRTB.Producer] = deriveCodec
    implicit val c11: Codec[OpenRTB.Data] = deriveCodec
    implicit val c12: Codec[OpenRTB.Content] = deriveCodec
    implicit val c13: Codec[OpenRTB.Publisher] = deriveCodec
    implicit val c14: Codec[OpenRTB.Geo] = deriveCodec
    implicit val c15: Codec[OpenRTB.Imp] = deriveCodec
    implicit val c16: Codec[OpenRTB.Site] = deriveCodec
    implicit val c17: Codec[OpenRTB.App] = deriveCodec
    implicit val c18: Codec[OpenRTB.Device] = deriveCodec
    implicit val c19: Codec[OpenRTB.User] = deriveCodec
    implicit val c20: Codec[OpenRTB.Source] = deriveCodec
    implicit val c21: Codec[OpenRTB.Reqs] = deriveCodec
    deriveCodec[OpenRTB.BidRequest]
  }
  implicit val Codec(suitADTEnc: Encoder[SuitADT], suitADTDec: Decoder[SuitADT]) = stringCodec {
    val m = Map[String, SuitADT](
      "Hearts" -> Hearts,
      "Spades" -> Spades,
      "Diamonds" -> Diamonds,
      "Clubs" -> Clubs)
    s => m.getOrElse(s, throw new IllegalArgumentException("SuitADT"))
  }
  implicit val Codec(suitEnc: Encoder[Suit], suitDec: Decoder[Suit]) = stringCodec(Suit.valueOf)
  implicit val Codec(suitEnumEnc: Encoder[SuitEnum], suitEnumDec: Decoder[SuitEnum]) = stringCodec(SuitEnum.withName)
  implicit val Codec(twitterAPIEnc: Encoder[TwitterAPI.Tweet], twitterAPIDec: Decoder[TwitterAPI.Tweet]) = {
    import io.bullet.borer.NullOptions.{encoder, decoder}

    implicit val c1: Codec[TwitterAPI.UserMentions] = deriveCodec
    implicit val c2: Codec[TwitterAPI.Urls] = deriveCodec
    implicit val c3: Codec[TwitterAPI.Entities] = deriveCodec
    implicit val c4: Codec[TwitterAPI.Url] = deriveCodec
    implicit val c5: Codec[TwitterAPI.UserEntities] = deriveCodec
    implicit val c6: Codec[TwitterAPI.User] = deriveCodec
    implicit val c7: Codec[TwitterAPI.RetweetedStatus] = deriveCodec
    deriveCodec[TwitterAPI.Tweet]
  }
  implicit val Codec(uuidEnc: Encoder[UUID], uuidDec: Decoder[UUID]) = stringCodec(UUID.fromString)

  private[this] def stringCodec[T](f: String => T): Codec[T] =
    new Codec((w: Writer, value: T) => w.writeString(value.toString), (r: Reader) => f(r.readString()))
}