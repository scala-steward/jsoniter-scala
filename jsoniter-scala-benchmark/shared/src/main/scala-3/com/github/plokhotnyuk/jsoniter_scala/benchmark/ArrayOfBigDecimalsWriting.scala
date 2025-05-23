package com.github.plokhotnyuk.jsoniter_scala.benchmark

import org.openjdk.jmh.annotations.Benchmark

class ArrayOfBigDecimalsWriting extends ArrayOfBigDecimalsBenchmark {
  @Benchmark
  def borer(): Array[Byte] = {
    import io.bullet.borer.Json

    Json.encode(obj).toByteArray
  }

  @Benchmark
  def circe(): Array[Byte] = {
    import java.nio.charset.StandardCharsets.UTF_8
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.CirceEncodersDecoders._
    import io.circe.syntax._

    printer.print(obj.asJson).getBytes(UTF_8)
  }

  @Benchmark
  def circeJsoniter(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.CirceJsoniterCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.circe.CirceCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import io.circe.syntax._

    writeToArray(obj.asJson)
  }

  @Benchmark
  def jacksonScala(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JacksonSerDesers._

    jacksonMapper.writeValueAsBytes(obj)
  }

  @Benchmark
  def json4sJackson(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.BigDecimalJson4sFormat._
    import org.json4s._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.Json4sJacksonMappers._

    bigNumberMapper.writeValueAsBytes(Extraction.decompose(obj))
  }

  @Benchmark
  def json4sNative(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.CommonJson4sFormats._
    import org.json4s.native.Serialization._
    import java.nio.charset.StandardCharsets.UTF_8

    write(obj).getBytes(UTF_8)
  }

  @Benchmark
  def jsoniterScala(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JsoniterScalaCodecs._

    writeToArray(obj)
  }

  @Benchmark
  def jsoniterScalaPrealloc(): Int = {
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JsoniterScalaCodecs._

    writeToSubArray(obj, preallocatedBuf, 64, preallocatedBuf.length)
  }

  @Benchmark
  def playJson(): Array[Byte] = {
    import play.api.libs.json.Json

    Json.toBytes(Json.toJson(obj))
  }

  @Benchmark
  def playJsonJsoniter(): Array[Byte] = {
    import com.evolutiongaming.jsonitertool.PlayJsonJsoniter._
    import play.api.libs.json.Json
    import com.github.plokhotnyuk.jsoniter_scala.core._

    writeToArray(Json.toJson(obj))
  }

  @Benchmark
  def smithy4sJson(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.Smithy4sJCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    writeToArray(obj)
  }

  @Benchmark
  def sprayJson(): Array[Byte] = {
    import spray.json._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.SprayFormats._
    import java.nio.charset.StandardCharsets.UTF_8

    obj.toJson.compactPrint.getBytes(UTF_8)
  }

  @Benchmark
  def uPickle(): Array[Byte] = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.UPickleReaderWriters._

    writeToByteArray(obj)
  }
/* FIXME: weePickle writes BigDecimal as JSON strings by default
  @Benchmark
  def weePickle(): Array[Byte] = {
    import com.rallyhealth.weejson.v1.jackson.ToJson
    import com.rallyhealth.weepickle.v1.WeePickle.FromScala

    FromScala(obj).transform(ToJson.bytes)
  }
*/
  @Benchmark
  def zioJson(): Array[Byte] = {
    import zio.json.EncoderOps
    import java.nio.charset.StandardCharsets.UTF_8

    obj.toJson.getBytes(UTF_8)
  }
}