package com.github.plokhotnyuk.jsoniter_scala.benchmark

import org.openjdk.jmh.annotations.Benchmark

class ArrayOfFloatsReading extends ArrayOfFloatsBenchmark {
  @Benchmark
  def borer(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.BorerJsonEncodersDecoders._
    import io.bullet.borer.Json

    Json.decode(jsonBytes).withConfig(decodingConfig).to[Array[Float]].value
  }

  @Benchmark
  def circe(): Array[Float] = {
    import io.circe.jawn._

    decodeByteArray[Array[Float]](jsonBytes).fold(throw _, identity)
  }

  @Benchmark
  def circeJsoniter(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.CirceJsoniterCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.circe.CirceCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import io.circe.Decoder

    Decoder[Array[Float]].decodeJson(readFromArray(jsonBytes)).fold(throw _, identity)
  }

  @Benchmark
  def jacksonScala(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JacksonSerDesers._

    jacksonMapper.readValue[Array[Float]](jsonBytes)
  }

  @Benchmark
  @annotation.nowarn
  def json4sJackson(): Array[Float] = {
    import org.json4s._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.Json4sJacksonMappers._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.BigDecimalJson4sFormat._

    bigNumberMapper.readValue[JValue](jsonBytes, jValueType).extract[Array[Float]]
  }

  @Benchmark
  @annotation.nowarn
  def json4sNative(): Array[Float] = {
    import org.json4s._
    import org.json4s.native.JsonMethods._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.BigDecimalJson4sFormat._
    import java.nio.charset.StandardCharsets.UTF_8

    parse(new String(jsonBytes, UTF_8), useBigDecimalForDouble = true).extract[Array[Float]]
  }

  @Benchmark
  def jsoniterScala(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JsoniterScalaCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    readFromArray[Array[Float]](jsonBytes)
  }

  @Benchmark
  def playJson(): Array[Float] = {
    import play.api.libs.json.Json

    Json.parse(jsonBytes).as[Array[Float]]
  }

  @Benchmark
  def playJsonJsoniter(): Array[Float] = {
    import com.evolutiongaming.jsonitertool.PlayJsonJsoniter._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    readFromArray[play.api.libs.json.JsValue](jsonBytes).as[Array[Float]]
  }

  @Benchmark
  def smithy4sJson(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.Smithy4sJCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    readFromArray[Array[Float]](jsonBytes)
  }

  @Benchmark
  def sprayJson(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.SprayFormats._
    import spray.json._

    JsonParser(jsonBytes).convertTo[Array[Float]]
  }

  @Benchmark
  def uPickle(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.UPickleReaderWriters._

    read[Array[Float]](jsonBytes)
  }

  @Benchmark
  def weePickle(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.WeePickleFromTos._
    import com.rallyhealth.weepickle.v1.WeePickle.ToScala

    FromJson(jsonBytes).transform(ToScala[Array[Float]])
  }

  @Benchmark
  def zioJson(): Array[Float] = {
    import zio.json.DecoderOps
    import java.nio.charset.StandardCharsets.UTF_8

    new String(jsonBytes, UTF_8).fromJson[Array[Float]].fold(sys.error, identity)
  }

  @Benchmark
  def zioSchemaJson(): Array[Float] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.ZioSchemaJsonCodecs._
    import java.nio.charset.StandardCharsets.UTF_8

    arrayOfFloatsCodec.decodeJson(new String(jsonBytes, UTF_8)).fold(sys.error, identity)
  }
}