package com.github.plokhotnyuk.jsoniter_scala.benchmark

class ArrayOfBytesWritingSpec extends BenchmarkSpecBase {
  def benchmark: ArrayOfBytesWriting = new ArrayOfBytesWriting {
    setup()
  }

  "ArrayOfBytesWriting" should {
    "write properly" in {
      val b = benchmark
      toString(b.avSystemGenCodec()) shouldBe b.jsonString
      toString(b.borer()) shouldBe b.jsonString
      toString(b.circe()) shouldBe b.jsonString
      toString(b.circeJsoniter()) shouldBe b.jsonString
      toString(b.jsoniterScala()) shouldBe b.jsonString
      toString(b.preallocatedBuf, 64, b.jsoniterScalaPrealloc()) shouldBe b.jsonString
      toString(b.playJson()) shouldBe b.jsonString
      toString(b.playJsonJsoniter()) shouldBe b.jsonString
      toString(b.smithy4sJson()) shouldBe b.jsonString
      toString(b.uPickle()) shouldBe b.jsonString
      toString(b.zioJson()) shouldBe b.jsonString
      toString(b.zioSchemaJson()) shouldBe b.jsonString
    }
  }
}