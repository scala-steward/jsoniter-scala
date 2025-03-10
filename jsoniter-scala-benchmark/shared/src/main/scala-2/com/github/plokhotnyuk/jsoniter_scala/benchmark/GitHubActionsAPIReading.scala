package com.github.plokhotnyuk.jsoniter_scala.benchmark

import org.openjdk.jmh.annotations.Benchmark

class GitHubActionsAPIReading extends GitHubActionsAPIBenchmark {
  @Benchmark
  def avSystemGenCodec(): GitHubActionsAPI.Response = {
    import com.avsystem.commons.serialization.json._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.AVSystemCodecs._
    import java.nio.charset.StandardCharsets.UTF_8

    JsonStringInput.read[GitHubActionsAPI.Response](new String(jsonBytes, UTF_8))
  }

  @Benchmark
  def borer(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.BorerJsonEncodersDecoders._
    import io.bullet.borer.Json

    Json.decode(jsonBytes).to[GitHubActionsAPI.Response].value
  }

  @Benchmark
  def circe(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.CirceEncodersDecoders._
    import io.circe.jawn._

    decodeByteArray[GitHubActionsAPI.Response](jsonBytes).fold(throw _, identity)
  }

  @Benchmark
  def circeJsoniter(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.CirceJsoniterCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._
    import io.circe.Decoder

    Decoder[GitHubActionsAPI.Response].decodeJson(readFromArray(jsonBytes)).fold(throw _, identity)
  }

  @Benchmark
  def jacksonScala(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JacksonSerDesers._

    jacksonMapper.readValue[GitHubActionsAPI.Response](jsonBytes)
  }

  @Benchmark
  def json4sJackson(): GitHubActionsAPI.Response = {
    import org.json4s._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.Json4sJacksonMappers._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.GitHubActionsAPIJson4sFormats._

    mapper.readValue[JValue](jsonBytes, jValueType).extract[GitHubActionsAPI.Response]
  }

  @Benchmark
  def json4sNative(): GitHubActionsAPI.Response = {
    import org.json4s._
    import org.json4s.native.JsonMethods._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.GitHubActionsAPIJson4sFormats._
    import java.nio.charset.StandardCharsets.UTF_8

    parse(new String(jsonBytes, UTF_8)).extract[GitHubActionsAPI.Response]
  }

  @Benchmark
  def jsoniterScala(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.JsoniterScalaCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    readFromArray[GitHubActionsAPI.Response](jsonBytes)
  }

  @Benchmark
  def playJson(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.PlayJsonFormats._
    import play.api.libs.json.Json

    Json.parse(jsonBytes).as[GitHubActionsAPI.Response]
  }

  @Benchmark
  def playJsonJsoniter(): GitHubActionsAPI.Response = {
    import com.evolutiongaming.jsonitertool.PlayJsonJsoniter._
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.PlayJsonJsoniterFormats._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    readFromArray(jsonBytes).as[GitHubActionsAPI.Response]
  }

  @Benchmark
  def smithy4sJson(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.Smithy4sJCodecs._
    import com.github.plokhotnyuk.jsoniter_scala.core._

    readFromArray[GitHubActionsAPI.Response](jsonBytes)
  }

  @Benchmark
  def sprayJson(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.SprayFormats._
    import spray.json.JsonParser

    JsonParser(jsonBytes).convertTo[GitHubActionsAPI.Response]
  }

  @Benchmark
  def uPickle(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.UPickleReaderWriters._

    read[GitHubActionsAPI.Response](jsonBytes)
  }

  @Benchmark
  def weePickle(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.WeePickleFromTos._
    import com.rallyhealth.weepickle.v1.WeePickle.ToScala

    FromJson(jsonBytes).transform(ToScala[GitHubActionsAPI.Response])
  }

  @Benchmark
  def zioJson(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.ZioJsonCodecs._
    import zio.json.DecoderOps
    import java.nio.charset.StandardCharsets.UTF_8

    new String(jsonBytes, UTF_8).fromJson[GitHubActionsAPI.Response].fold(sys.error, identity)
  }

  @Benchmark
  def zioSchemaJson(): GitHubActionsAPI.Response = {
    import com.github.plokhotnyuk.jsoniter_scala.benchmark.ZioSchemaJsonCodecs._
    import java.nio.charset.StandardCharsets.UTF_8

    gitHubActionsAPICodec.decodeJson(new String(jsonBytes, UTF_8)).fold(sys.error, identity)
  }
}