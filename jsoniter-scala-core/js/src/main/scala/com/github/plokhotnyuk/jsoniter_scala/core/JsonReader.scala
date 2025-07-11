package com.github.plokhotnyuk.jsoniter_scala.core

import java.io.InputStream
import java.math.MathContext
import java.nio.ByteBuffer
import java.time._
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader._
import java.nio.charset.StandardCharsets.UTF_8
import scala.annotation.{switch, tailrec}
import scala.{specialized => sp}

/**
  * The reader to parse JSON input iteratively.
  *
  * @param buf the internal buffer with JSON input
  * @param head the head position in the internal buffer
  * @param tail the tail position in the internal buffer
  * @param mark the current mark position
  * @param charBuf the internal char buffer for parsed strings
  * @param bbuf the byte buffer with JSON input
  * @param in the input stream with JSON input
  * @param totalRead the total number of read bytes
  * @param config the JSON reader configuration
  */
final class JsonReader private[jsoniter_scala](
    private[this] var buf: Array[Byte] = new Array[Byte](32768),
    private[this] var head: Int = 0,
    private[this] var tail: Int = 0,
    private[this] var mark: Int = -1,
    private[this] var charBuf: Array[Char] = new Array[Char](4096),
    private[this] var bbuf: ByteBuffer = null,
    private[this] var in: InputStream = null,
    private[this] var totalRead: Long = 0,
    private[this] var config: ReaderConfig = null) {
  private[this] var magnitude: Array[Int] = _
  private[this] var zoneIdKey: Key = _

  /**
    * Throws a [[JsonReaderException]] indicating that a required field with the given name is missing.
    *
    * @param reqField the name of the missing required field
    * @throws JsonReaderException always
    */
  def requiredFieldError(reqField: String): Nothing = {
    var i = appendString("missing required field \"", 0)
    i = appendString(reqField, i)
    i = appendChar('"', i)
    decodeError(i, head - 1, null)
  }

  /**
    * Throws a [[JsonReaderException]] indicating that a field with the given name
    * is duplicated.
    *
    * @param len the length of the duplicated field name in the internal char buffer
    * @throws JsonReaderException always
    */
  def duplicatedKeyError(len: Int): Nothing = {
    var i = prependString("duplicated field \"", len)
    i = appendChar('"', i)
    decodeError(i, head - 1, null)
  }

  /**
    * Throws a [[JsonReaderException]] indicating that an unexpected field with the given name was encountered.
    *
    * @param len the length of the unexpected field name in the internal char buffer
    * @throws JsonReaderException always
    */
  def unexpectedKeyError(len: Int): Nothing = {
    var i = prependString("unexpected field \"", len)
    i = appendChar('"', i)
    decodeError(i, head - 1, null)
  }

  /**
    * Throws a [[JsonReaderException]] indicating that an illegal discriminator field name was encountered.
    */
  def discriminatorError(): Nothing = decodeError("illegal discriminator")

  /**
    * Throws a [[JsonReaderException]] indicating that an illegal value was encountered for the given discriminator field.
    *
    * @param discriminatorFieldName the name of the discriminator field
    * @throws JsonReaderException always
    */
  def discriminatorValueError(discriminatorFieldName: String): Nothing = {
    var i = appendString("illegal value of discriminator field \"", 0)
    i = appendString(discriminatorFieldName, i)
    i = appendChar('"', i)
    decodeError(i, head - 1, null)
  }

  /**
    * Throws a [[JsonReaderException]] indicating that an illegal enum value was encountered.
    *
    * @param value an illegal enum value
    * @throws JsonReaderException always
    */
  def enumValueError(value: String): Nothing = {
    var i = appendString("illegal enum value \"", 0)
    i = appendString(value, i)
    i = appendChar('"', i)
    decodeError(i, head - 1, null)
  }

  /**
    * Throws a [[JsonReaderException]] indicating that an illegal enum value with the given length was encountered.
    *
    * @param len the length of the illegal enum value in the internal char buffer.
    * @throws JsonReaderException always
    */
  def enumValueError(len: Int): Nothing = {
    var i = prependString("illegal enum value \"", len)
    i = appendChar('"', i)
    decodeError(i, head - 1, null)
  }

  /**
    * Sets the current read head position as a mark.
    * Should be followed by `resetMark()` or `rollbackToMark()` calls.
    * Pair of `setMark()` and `resetMark()` or `setMark()` and `rollbackToMark()` calls cannot be nested.
    */
  def setMark(): Unit = mark = head

  /**
    * Skips tokens with in the current JSON object until a key with the given name is encountered.
    *
    * @param key the name of the JSON key to skip to
    * @return `true` if the key was found, `false` otherwise
    * @throws JsonReaderException in cases of reaching the end of input or invalid encoding of JSON key
    */
  def skipToKey(key: String): Boolean = {
    while (!isCharBufEqualsTo(readKeyAsCharBuf(), key)) {
      skip()
      if (!isNextToken(',', head)) return false
    }
    true
  }

  /**
    * Rolls back the read head position to the previously set mark.
    *
    * @throws java.lang.IllegalStateException in case of calling without preceding call of 'setMark()'
    */
  def rollbackToMark(): Unit = {
    if (mark < 0) missingSetMarkOperation()
    head = mark
    mark = -1
  }

  /**
   * Reset mark without changing of the read head position.
   *
   * @throws java.lang.IllegalStateException in case of calling without preceding call of 'setMark()'
   */
  def resetMark(): Unit = {
    if (mark < 0) missingSetMarkOperation()
    mark = -1
  }

  /**
    * Reads a JSON key into the internal char buffer and returns the length of the key.
    *
    * @return the length of the key in the internal char buffer
    * @throws JsonReaderException in cases of reaching the end of input or invalid encoding of JSON key
    */
  def readKeyAsCharBuf(): Int = {
    nextTokenOrError('"', head)
    val pos = head
    val len = parseString(0, Math.min(tail - pos, charBuf.length), charBuf, pos)
    nextTokenOrError(':', head)
    len
  }

  /**
    * Reads a JSON key into the internal char buffer and returns a `String` instance.
    *
    * @return a `String` instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or invalid encoding of JSON key
    */
  def readKeyAsString(): String = {
    nextTokenOrError('"', head)
    val pos = head
    val len = parseString(0, Math.min(tail - pos, charBuf.length), charBuf, pos)
    nextTokenOrError(':', head)
    new String(charBuf, 0, len)
  }

  /**
    * Reads a JSON key into a [[java.time.Duration]] instance.
    *
    * @return a [[java.time.Duration]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsDuration(): Duration = {
    nextTokenOrError('"', head)
    val x = parseDuration()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.Instant]] instance.
    *
    * @return a [[java.time.Instant]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsInstant(): Instant = {
    nextTokenOrError('"', head)
    val x = parseInstant()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.LocalDate]] instance.
    *
    * @return a [[java.time.LocalDate]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsLocalDate(): LocalDate = {
    nextTokenOrError('"', head)
    val x = parseLocalDate()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.LocalDateTime]] instance.
    *
    * @return a [[java.time.LocalDateTime]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsLocalDateTime(): LocalDateTime = {
    nextTokenOrError('"', head)
    val x = parseLocalDateTime()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.LocalTime]] instance.
    *
    * @return a [[java.time.LocalTime]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsLocalTime(): LocalTime = {
    nextTokenOrError('"', head)
    val x = parseLocalTime()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.MonthDay]] instance.
    *
    * @return a [[java.time.MonthDay]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsMonthDay(): MonthDay = {
    nextTokenOrError('"', head)
    val x = parseMonthDay(head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.OffsetDateTime]] instance.
    *
    * @return a [[java.time.OffsetDateTime]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsOffsetDateTime(): OffsetDateTime = {
    nextTokenOrError('"', head)
    val x = parseOffsetDateTime()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.OffsetTime]] instance.
    *
    * @return a [[java.time.OffsetTime]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsOffsetTime(): OffsetTime = {
    nextTokenOrError('"', head)
    val x = parseOffsetTime()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.Period]] instance.
    *
    * @return a [[java.time.Period]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsPeriod(): Period = {
    nextTokenOrError('"', head)
    val x = parsePeriod()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.Year]] instance.
    *
    * @return a [[java.time.Year]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsYear(): Year = {
    nextTokenOrError('"', head)
    val x = Year.of(parseYearWithByte('"', 9, head))
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.YearMonth]] instance.
    *
    * @return a [[java.time.YearMonth]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsYearMonth(): YearMonth = {
    nextTokenOrError('"', head)
    val x = parseYearMonth()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.ZonedDateTime]] instance.
    *
    * @return a [[java.time.ZonedDateTime]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsZonedDateTime(): ZonedDateTime = {
    nextTokenOrError('"', head)
    val x = parseZonedDateTime()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.ZoneId]] instance.
    *
    * @return a [[java.time.ZoneId]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsZoneId(): ZoneId = {
    nextTokenOrError('"', head)
    val x = parseZoneId()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.time.ZoneOffset]] instance.
    *
    * @return a [[java.time.ZoneOffset]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsZoneOffset(): ZoneOffset = {
    nextTokenOrError('"', head)
    val x = parseZoneOffset()
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Boolean` value.
    *
    * @return a `Boolean` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsBoolean(): Boolean = {
    nextTokenOrError('"', head)
    val x = parseBoolean(isToken = false, head)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Byte` value.
    *
    * @return a `Byte` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsByte(): Byte = {
    nextTokenOrError('"', head)
    val x = readByte(isToken = false)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Char` value.
    *
    * @return a `Char` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    *                             or exceeding capacity of `Char` or when parsed char is a part of a surrogate pair
    */
  def readKeyAsChar(): Char = {
    nextTokenOrError('"', head)
    val x = parseChar(head)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Short` value.
    *
    * @return a `Short` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsShort(): Short = {
    nextTokenOrError('"', head)
    val x = readShort(isToken = false)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Int` value.
    *
    * @return a `Int` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsInt(): Int = {
    nextTokenOrError('"', head)
    val x = readInt(isToken = false)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Long` value.
    *
    * @return a `Long` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsLong(): Long = {
    nextTokenOrError('"', head)
    val x = readLong(isToken = false)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Float` value.
    *
    * @return a `Float` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsFloat(): Float = {
    nextTokenOrError('"', head)
    val x = readFloat(isToken = false)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `Double` value.
    *
    * @return a `Double` value of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsDouble(): Double = {
    nextTokenOrError('"', head)
    val x = readDouble(isToken = false)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `BigInt` instance with the default limit of allowed digits.
    *
    * @return a `BigInt` instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key or
    *                             exceeding of the default limit
    */
  def readKeyAsBigInt(): BigInt = readKeyAsBigInt(bigIntDigitsLimit)

  /**
    * Reads a JSON key into a `BigInt` instance with the given limit of allowed digits.
    *
    * @param digitsLimit the maximum number of decimal digits allowed in the parsed `BigInt` value
    * @return a `BigInt` instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key or
    *                             exceeding of the provided limit
    */
  def readKeyAsBigInt(digitsLimit: Int): BigInt = {
    nextTokenOrError('"', head)
    val x = readBigInt(isToken = false, null, digitsLimit)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a `BigDecimal` instance with the default limit of allowed digits for mantissa,
    * the default limit for scale, and the default instance of [[java.math.MathContext]] for precision.
    *
    * @return a `BigDecimal` instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key or
    *                             exceeding of default limits
    */
  def readKeyAsBigDecimal(): BigDecimal =
    readKeyAsBigDecimal(bigDecimalMathContext, bigDecimalScaleLimit, bigDecimalDigitsLimit)

  /**
    * Reads a JSON key into a Scala `BigDecimal` instance with the given precision, scale limit, and digits limit.
    *
    * @param mc the precision to use
    * @param scaleLimit the maximum number of decimal places (scale) allowed
    * @param digitsLimit the maximum number of decimal digits allowed
    * @return a `BigDecimal` instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key or
    *                             exceeding of provided limits
    */
  def readKeyAsBigDecimal(mc: MathContext, scaleLimit: Int, digitsLimit: Int): BigDecimal = {
    nextTokenOrError('"', head)
    val x = readBigDecimal(isToken = false, null, mc, scaleLimit, digitsLimit)
    nextByteOrError('"', head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON key into a [[java.util.UUID]] instance.
    *
    * @return a [[java.util.UUID]] instance of the parsed JSON key
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON key
    */
  def readKeyAsUUID(): UUID = {
    nextTokenOrError('"', head)
    val x = parseUUID(head)
    nextTokenOrError(':', head)
    x
  }

  /**
    * Reads a JSON number value into a `Byte` value.
    *
    * @return a `Byte` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or dection of leading zero or
    *                             illegal format of JSON value or exceeding capacity of `Byte`
    */
  @inline
  def readByte(): Byte = readByte(isToken = true)

  /**
    * Reads a JSON string value into a `Char` value.
    *
    * @return a `Char` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or invalid encoding of JSON value or
    *                             exceeding capacity of `Char` or when parsed char is a part of a surrogate pair
    */
  def readChar(): Char = {
    nextTokenOrError('"', head)
    val x = parseChar(head)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON number value into a `Short` value.
    *
    * @return a `Short` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding capacity of `Short`
    */
  @inline
  def readShort(): Short = readShort(isToken = true)

  /**
    * Reads a JSON value into a `Int` value.
    *
    * @return a `Int` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding capacity of `Int`
    */
  @inline
  def readInt(): Int = readInt(isToken = true)

  /**
    * Reads a JSON number value into a `Long` value.
    *
    * @return a `Long` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding capacity of `Long`
    */
  @inline
  def readLong(): Long = readLong(isToken = true)

  /**
    * Reads a JSON number value into a `Double` value.
    *
    * @return a `Double` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value
    */
  @inline
  def readDouble(): Double = readDouble(isToken = true)

  /**
    * Reads a JSON number value into a `Float` value.
    *
    * @return a `Float` value of the parsed JSON value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value
    */
  @inline
  def readFloat(): Float = readFloat(isToken = true)

  /**
    * Reads a JSON number value into a `BigInt` instance with the default limit of allowed digits.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigInt` value to return if the JSON value is `null`
    * @return a `BigInt` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding of the default limit or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBigInt(default: BigInt): BigInt = readBigInt(isToken = true, default, bigIntDigitsLimit)

  /**
    * Reads a JSON number value into a `BigInt` instance with the provided limit of allowed digits.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigInt` value to return if the JSON value is `null`
    * @param digitsLimit the maximum number of decimal digits allowed in the parsed `BigInt` value
    * @return a `BigInt` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding of the default limit or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBigInt(default: BigInt, digitsLimit: Int): BigInt = readBigInt(isToken = true, default, digitsLimit)

  /**
    * Reads a JSON number value into a `BigDecimal` instance with the default limit of allowed digits for mantissa,
    * the default limit for scale, and the default instance of [[java.math.MathContext]] for precision.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigDecimal` value to return if the JSON value is `null`
    * @return a `BigDecimal` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding of default limits or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBigDecimal(default: BigDecimal): BigDecimal =
    readBigDecimal(isToken = true, default, bigDecimalMathContext, bigDecimalScaleLimit, bigDecimalDigitsLimit)

  /**
    * Reads a JSON number value into a `BigDecimal` instance with the provided limit of allowed digits for mantissa,
    * the provided limit for scale, and the provided instance of [[java.math.MathContext]] for precision.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigDecimal` value to return if the JSON value is `null`
    * @param mc the precision to use
    * @param scaleLimit the maximum number of decimal places (scale) allowed
    * @param digitsLimit the maximum number of decimal digits allowed
    * @return a `BigDecimal` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or detection of leading zero or
    *                             illegal format of JSON value or exceeding of provided limits or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBigDecimal(default: BigDecimal, mc: MathContext, scaleLimit: Int, digitsLimit: Int): BigDecimal =
    readBigDecimal(isToken = true, default, mc, scaleLimit, digitsLimit)

  /**
    * Reads a JSON string value into a `String` instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `String` value to return if the JSON value is `null`.
    * @return a `String` instance of the parsed JSON value or the default value if the JSON value is `null`.
    * @throws JsonReaderException in cases of reaching the end of input or invalid encoding of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readString(default: String): String =
    if (isNextToken('"', head)) {
      val pos = head
      val len = parseString(0, Math.min(tail - pos, charBuf.length), charBuf, pos)
      new String(charBuf, 0, len)
    } else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.Duration]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.Duration]] value to return if the JSON value is `null`
    * @return a [[java.time.Duration]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readDuration(default: Duration): Duration =
    if (isNextToken('"', head)) parseDuration()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.Instant]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.Instant]] value to return if the JSON value is `null`
    * @return a [[java.time.Instant]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readInstant(default: Instant): Instant =
    if (isNextToken('"', head)) parseInstant()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.LocalDate]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.LocalDate]] value to return if the JSON value is `null`
    * @return a [[java.time.LocalDate]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readLocalDate(default: LocalDate): LocalDate =
    if (isNextToken('"', head)) parseLocalDate()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.LocalDateTime]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.LocalDateTime]] value to return if the JSON value is `null`
    * @return a [[java.time.LocalDateTime]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readLocalDateTime(default: LocalDateTime): LocalDateTime =
    if (isNextToken('"', head)) parseLocalDateTime()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.LocalTime]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.LocalTime]] value to return if the JSON value is `null`
    * @return a [[java.time.LocalTime]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readLocalTime(default: LocalTime): LocalTime =
    if (isNextToken('"', head)) parseLocalTime()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.MonthDay]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.MonthDay]] value to return if the JSON value is `null`
    * @return a [[java.time.MonthDay]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readMonthDay(default: MonthDay): MonthDay =
    if (isNextToken('"', head)) parseMonthDay(head)
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.OffsetDateTime]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.OffsetDateTime]] value to return if the JSON value is `null`
    * @return a [[java.time.OffsetDateTime]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readOffsetDateTime(default: OffsetDateTime): OffsetDateTime =
    if (isNextToken('"', head)) parseOffsetDateTime()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.OffsetTime]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.OffsetTime]] value to return if the JSON value is `null`.
    * @return a [[java.time.OffsetTime]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readOffsetTime(default: OffsetTime): OffsetTime =
    if (isNextToken('"', head)) parseOffsetTime()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.Period]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.Period]] value to return if the JSON value is `null`
    * @return a [[java.time.Period]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readPeriod(default: Period): Period =
    if (isNextToken('"', head)) parsePeriod()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.Year]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.Year]] value to return if the JSON value is `null`
    * @return a [[java.time.Year]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readYear(default: Year): Year =
    if (isNextToken('"', head)) Year.of(parseYearWithByte('"', 9, head))
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.YearMonth]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.YearMonth]] value to return if the JSON value is `null`
    * @return a [[java.time.YearMonth]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readYearMonth(default: YearMonth): YearMonth =
    if (isNextToken('"', head)) parseYearMonth()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.ZonedDateTime]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.ZonedDateTime]] value to return if the JSON value is `null`
    * @return a [[java.time.ZonedDateTime]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readZonedDateTime(default: ZonedDateTime): ZonedDateTime =
    if (isNextToken('"', head)) parseZonedDateTime()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.ZoneId]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.ZoneId]] value to return if the JSON value is `null`
    * @return a [[java.time.ZoneId]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readZoneId(default: ZoneId): ZoneId =
    if (isNextToken('"', head)) parseZoneId()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.time.ZoneOffset]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.time.ZoneOffset]] value to return if the JSON value is `null`
    * @return a [[java.time.ZoneOffset]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readZoneOffset(default: ZoneOffset): ZoneOffset =
    if (isNextToken('"', head)) parseZoneOffset()
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a [[java.util.UUID]] instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default [[java.util.UUID]] value to return if the JSON value is `null`
    * @return a [[java.util.UUID]] instance of the parsed JSON value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readUUID(default: UUID): UUID =
    if (isNextToken('"', head)) parseUUID(head)
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value encoded with the base-16 format into a `Array[Byte]` instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `Array[Byte]` value to return if the JSON value is `null`
    * @return a `Array[Byte]` instance of the parsed JSON  value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBase16AsBytes(default: Array[Byte]): Array[Byte] =
    if (isNextToken('"', head)) parseBase16(nibbles)
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value encoded with the base-64 format into a `Array[Byte]` instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `Array[Byte]` value to return if the JSON value is `null`
    * @return a `Array[Byte]` instance of the parsed JSON  value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBase64AsBytes(default: Array[Byte]): Array[Byte] =
    if (isNextToken('"', head)) parseBase64(base64Bytes)
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value encoded with the base-64 format for URLs into a `Array[Byte]` instance.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `Array[Byte]` value to return if the JSON value is `null`
    * @return a `Array[Byte]` instance of the parsed JSON  value or the default value if the JSON value is `null`
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readBase64UrlAsBytes(default: Array[Byte]): Array[Byte] =
    if (isNextToken('"', head)) parseBase64(base64UrlBytes)
    else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON boolean value into a `Boolean` value.
    *
    * @return a `Boolean` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or unexpected JSON value
    */
  def readBoolean(): Boolean = parseBoolean(isToken = true, head)

  /**
    * Reads a JSON string value into the internal char buffer and returns the length of the string.
    *
    * @return a length of the string in the internal char buffer
    * @throws JsonReaderException in cases of reaching the end of input or invalid encoding of JSON value
    */
  def readStringAsCharBuf(): Int = {
    nextTokenOrError('"', head)
    val pos = head
    parseString(0, Math.min(tail - pos, charBuf.length), charBuf, pos)
  }

  /**
    * Reads a JSON string value into a `Byte` value.
    *
    * @return a `Byte` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             exceeding capacity of `Byte`
    */
  def readStringAsByte(): Byte = {
    nextTokenOrError('"', head)
    val x = readByte(isToken = false)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON string value into a `Short` value.
    *
    * @return a `Short` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             exceeding capacity of `Short`
    */
  def readStringAsShort(): Short = {
    nextTokenOrError('"', head)
    val x = readShort(isToken = false)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON string value into a `Int` value.
    *
    * @return a `Int` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             exceeding capacity of `Int`
    */
  def readStringAsInt(): Int = {
    nextTokenOrError('"', head)
    val x = readInt(isToken = false)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON string value into a `Long` value.
    *
    * @return a `Long` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value or
    *                             exceeding capacity of `Long`
    */
  def readStringAsLong(): Long = {
    nextTokenOrError('"', head)
    val x = readLong(isToken = false)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON string value into a `Double` value.
    *
    * @return a `Double` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value
    */
  def readStringAsDouble(): Double = {
    nextTokenOrError('"', head)
    val x = readDouble(isToken = false)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON string value into a `Float` value.
    *
    * @return a `Float` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value
    */
  def readStringAsFloat(): Float = {
    nextTokenOrError('"', head)
    val x = readFloat(isToken = false)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a JSON string value into a `BigInt` instance with the default limit of allowed digits.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigInt` value to return if the JSON value is `null`
    * @return a `BigInt` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or
    *                             illegal format of JSON value or exceeding of the default limit or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readStringAsBigInt(default: BigInt): BigInt = readStringAsBigInt(default, bigIntDigitsLimit)

  /**
    * Reads a JSON string value into a `BigInt` instance with the provided limit of allowed digits.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigInt` value to return if the JSON value is `null`
    * @param digitsLimit the maximum number of decimal digits allowed in the parsed `BigInt` value
    * @return a `BigInt` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or
    *                             illegal format of JSON value or exceeding of the default limit or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readStringAsBigInt(default: BigInt, digitsLimit: Int): BigInt =
    if (isNextToken('"', head)) {
      val x = readBigInt(isToken = false, default, digitsLimit)
      nextByteOrError('"', head)
      x
    } else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON number value into a `BigDecimal` instance with the default limit of allowed digits for mantissa,
    * the default limit for scale, and the default instance of [[java.math.MathContext]] for precision.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigDecimal` value to return if the JSON value is `null`
    * @return a `BigDecimal` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or
    *                             illegal format of JSON value or exceeding of default limits or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readStringAsBigDecimal(default: BigDecimal): BigDecimal =
    readStringAsBigDecimal(default, bigDecimalMathContext, bigDecimalScaleLimit, bigDecimalDigitsLimit)

  /**
    * Reads a JSON number value into a `BigDecimal` instance with the provided limit of allowed digits for mantissa,
    * the provided limit for scale, and the provided instance of [[java.math.MathContext]] for precision.
    * In case of `null` JSON value returns the provided default value or throws a [[JsonReaderException]]
    * if the provided default value is `null`.
    *
    * @param default the default `BigDecimal` value to return if the JSON value is `null`
    * @param mc the precision to use
    * @param scaleLimit the maximum number of decimal places (scale) allowed
    * @param digitsLimit the maximum number of decimal digits allowed
    * @return a `BigDecimal` instance of the parsed JSON value or the provided default value
    * @throws JsonReaderException in cases of reaching the end of input or
    *                             illegal format of JSON value or exceeding of provided limits or
    *                             when both the JSON value and the provided default value are `null`
    */
  def readStringAsBigDecimal(default: BigDecimal, mc: MathContext, scaleLimit: Int, digitsLimit: Int): BigDecimal =
    if (isNextToken('"', head)) {
      val x = readBigDecimal(isToken = false, default, mc, scaleLimit, digitsLimit)
      nextByteOrError('"', head)
      x
    } else readNullOrTokenError(default, '"')

  /**
    * Reads a JSON string value into a `Boolean` value.
    *
    * @return a `Boolean` value of the parsed JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value
    */
  def readStringAsBoolean(): Boolean = {
    nextTokenOrError('"', head)
    val x = parseBoolean(isToken = false, head)
    nextByteOrError('"', head)
    x
  }

  /**
    * Reads a raw JSON value into a `Array[Byte]` instance without parsing.
    *
    * @return a `Array[Byte]` instance containing the raw bytes of the JSON value.
    * @throws JsonReaderException in cases of reaching the end of input or invalid type of JSON value
    */
  def readRawValAsBytes(): Array[Byte] = {
    var from = head
    val oldMark = mark
    val newMark =
      if (oldMark < 0) from
      else oldMark
    mark = newMark
    skip()
    if (mark == 0) from -= newMark
    if (mark > oldMark) mark = oldMark
    val len = head - from
    val x = new Array[Byte](len)
    System.arraycopy(buf, from, x, 0, len)
    x
  }

  /**
    * Finishes reading the `null` JSON value and returns the provided default value or throws [[JsonReaderException]].
    * Before calling it the `n` token should be parsed already.
    *
    * @param default the default value to return
    * @param msg the exception message
    * @tparam A the type of the default value
    * @return the default value
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value
    *                             or when the provided default value is `null`
    */
  def readNullOrError[@sp A](default: A, msg: String): A =
    if (default != null && isCurrentToken('n', head)) parseNullOrError(default, msg, head)
    else decodeError(msg)

  /**
    * Finishes reading the `null` JSON value and returns the provided default value or throws [[JsonReaderException]]
    * with a message of expecting `null` or the provided token.
    * Before calling it the `n` token should be parsed already.
    *
    * @param default the default value to return
    * @param t the token for an error message
    * @tparam A the type of the default value
    * @return the default value
    * @throws JsonReaderException in cases of reaching the end of input or illegal format of JSON value
    *                             or when the provided default value is `null`
    */
  def readNullOrTokenError[@sp A](default: A, t: Byte): A =
    if (default != null) {
      if (isCurrentToken('n', head)) parseNullOrTokenError(default, t, head)
      else tokenOrNullError(t)
    } else tokenError(t)

  /**
    * Reads and returns the next byte from the input.
    *
    * @return the next byte from the input
    * @throws JsonReaderException in cases of reaching the end of input
    */
  def nextByte(): Byte = nextByte(head)

  /**
    * Skips whitespaces, then reads and returns the next byte from the input.
    *
    * @return the next token from the input
    * @throws JsonReaderException in cases of reaching the end of input
    */
  def nextToken(): Byte = nextToken(head)

  /**
    * Skips whitespaces, then checks if the next token in the input matches the given one.
    *
    * @param t the token to match
    * @return `true` if the next token matches `t`, `false` otherwise
    * @throws JsonReaderException in cases of reaching the end of input
    */
  def isNextToken(t: Byte): Boolean = isNextToken(t, head)

  /**
    * Checks if the current token in the input matches the given one.
    *
    * @param t the token to match
    * @return `true` if the current token matches `t`, `false` otherwise.
    * @throws java.lang.IllegalStateException if no any token was parsed yet
    */
  def isCurrentToken(t: Byte): Boolean = isCurrentToken(t, head)

  /**
    * Checks if there are more bytes available for reading in the input.
    *
    * @return `true` if there are more bytes available, `false` otherwise
    */
  def hasRemaining(): Boolean = head < tail || loadMore(head) < tail

  /**
    * Rolls back the current reading position by one.
    *
    * @throws java.lang.IllegalStateException if no any token was parsed yet
    */
  def rollbackToken(): Unit = {
    val pos = head
    if (pos == 0) illegalTokenOperation()
    head = pos - 1
  }

  /**
    * Calculates a hash code for the internal char buffer of the given length.
    *
    * @param len the length of the char buffer to hash
    * @return the hash code for the char buffer
    */
  def charBufToHashCode(len: Int): Int = {
    val cs = charBuf
    if (cs.length < len) throw new ArrayIndexOutOfBoundsException(len)
    var h, i = 0
    while (i < len) {
      h = (h << 5) + (cs(i) - h)
      i += 1
    }
    h
  }

  /**
    * Checks if the internal char buffer contains the given string.
    *
    * @param len the length of the char buffer to check
    * @param s the string to match
    * @return `true` if the char buffer contains `s`, `false` otherwise
    */
  def isCharBufEqualsTo(len: Int, s: String): Boolean = {
    if (s eq null) throw new NullPointerException
    if (s.length != len) return false
    val charBuf = this.charBuf
    var i = 0
    while (i < len) {
      if (s.charAt(i) != charBuf(i)) return false
      i += 1
    }
    true
  }

  /**
    * Skips the next JSON value.
    *
    * @throws JsonReaderException in cases of reaching the end of input
    */
  def skip(): Unit = {
    val b = nextToken(head)
    var pos = head
    if (b == '"') pos = skipString(evenBackSlashes = true, pos)
    else if ((b >= '0' && b <= '9') || b == '-') pos = skipNumber(pos)
    else if (b == 'n' || b == 't') pos = skipFixedBytes(3, pos)
    else if (b == 'f') pos = skipFixedBytes(4, pos)
    else if (b == '[') pos = skipArray(0, pos)
    else if (b == '{') pos = skipObject(0, pos)
    else decodeError("expected value")
    head = pos
  }

  /**
    * Throws a [[JsonReaderException]] with the message `expected ','`.
    *
    * @throws JsonReaderException always
    */
  def commaError(): Nothing = decodeError("expected ','")

  /**
    * Throws a [[JsonReaderException]] with the message `expected '[' or null`.
    *
    * @throws JsonReaderException always
    */
  def arrayStartOrNullError(): Nothing = decodeError("expected '[' or null")

  /**
    * Throws a [[JsonReaderException]] with the message `expected ']'`.
    *
    * @throws JsonReaderException always
    */
  def arrayEndError(): Nothing = decodeError("expected ']'")

  /**
    * Throws a [[JsonReaderException]] with the message `expected ']' or ','`.
    *
    * @throws JsonReaderException always
    */
  def arrayEndOrCommaError(): Nothing = decodeError("expected ']' or ','")

  /**
    * Throws a [[JsonReaderException]] with the message `expected '{' or null`.
    *
    * @throws JsonReaderException always
    */
  def objectStartOrNullError(): Nothing = decodeError("expected '{' or null")

  /**
    * Throws a [[JsonReaderException]] with the message `expected '}' or ','`.
    *
    * @throws JsonReaderException always
    */
  def objectEndOrCommaError(): Nothing = decodeError("expected '}' or ','")

  /**
    * Throws a [[JsonReaderException]] with the given message.
    *
    * @param msg the exception message
    * @throws JsonReaderException always
    */
  def decodeError(msg: String): Nothing = decodeError(msg, head - 1)

  /**
    * Reads a JSON value from the given byte array slice into an instance of type `A` using the given [[JsonValueCodec]].
    *
    * @param codec the JSON value codec
    * @param buf the byte array with JSON input
    * @param from the start index of the slice (inclusive)
    * @param to the end index of the slice (exclusive)
    * @param config the reader configuration
    * @tparam A the type of the value to read
    * @return an instance of type `A` containing the decoded JSON value
    * @throws JsonReaderException in cases of reaching the end of input during parsing of JSON value or
    *                             unexpected format of JSON value or when configured checking of reaching the end of input
    *                             doesn't pass after reading of the whole JSON value
    */
  private[jsoniter_scala] def read[@sp A](codec: JsonValueCodec[A], buf: Array[Byte], from: Int, to: Int, config: ReaderConfig): A = {
    val currBuf = this.buf
    try {
      this.buf = buf
      this.config = config
      head = from
      tail = to
      totalRead = 0
      mark = -1
      val x = codec.decodeValue(this, codec.nullValue)
      if (head != to && config.checkForEndOfInput) endOfInputOrError()
      x
    } finally {
      this.buf = currBuf
      if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
    }
  }

  /**
    * Reads a JSON value from the given input stream into an instance of type `A` using the given [[JsonValueCodec]].
    *
    * @param codec the JSON value codec
    * @param in the input stream with the JSON input
    * @param config the reader configuration
    * @tparam A the type of the value to read
    * @return an instance of type `A` containing the decoded JSON value
    * @throws JsonReaderException in cases of reaching the end of input during parsing of JSON value or
    *                             unexpected format of JSON value or when configured checking of reaching the end of input
    *                             doesn't pass after reading of the whole JSON value
    */
  private[jsoniter_scala] def read[@sp A](codec: JsonValueCodec[A], in: InputStream, config: ReaderConfig): A =
    try {
      this.config = config
      this.in = in
      head = 0
      tail = 0
      totalRead = 0
      mark = -1
      if (buf.length < config.preferredBufSize) reallocateBufToPreferredSize()
      val x = codec.decodeValue(this, codec.nullValue)
      if (config.checkForEndOfInput) endOfInputOrError()
      x
    } finally {
      this.in = null
      if (buf.length > config.preferredBufSize) reallocateBufToPreferredSize()
      if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
    }

  /**
    * Reads a JSON value from the given byte buffer into an instance of type `A` using the given [[JsonValueCodec]].
    *
    * @param codec the JSON value codec
    * @param bbuf the byte buffer with the JSON input
    * @param config the reader configuration
    * @tparam A the type of the value to read
    * @return an instance of type `A` containing the decoded JSON value
    * @throws JsonReaderException in cases of reaching the end of input during parsing of JSON value or
    *                             unexpected format of JSON value or when configured checking of reaching the end of input
    *                             doesn't pass after reading of the whole JSON value
    */
  private[jsoniter_scala] def read[@sp A](codec: JsonValueCodec[A], bbuf: ByteBuffer, config: ReaderConfig): A =
    if (bbuf.hasArray) {
      val offset = bbuf.arrayOffset
      val to = offset + bbuf.limit()
      val currBuf = this.buf
      try {
        this.buf = bbuf.array
        this.config = config
        head = offset + bbuf.position()
        tail = to
        totalRead = 0
        mark = -1
        val x = codec.decodeValue(this, codec.nullValue)
        if (head != to && config.checkForEndOfInput) endOfInputOrError()
        x
      } finally {
        this.buf = currBuf
        if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
        bbuf.position(head - offset)
      }
    } else {
      val position = bbuf.position()
      try {
        this.config = config
        this.bbuf = bbuf
        head = 0
        tail = 0
        totalRead = 0
        mark = -1
        if (buf.length < config.preferredBufSize) reallocateBufToPreferredSize()
        val x = codec.decodeValue(this, codec.nullValue)
        if (config.checkForEndOfInput) endOfInputOrError()
        x
      } finally {
        this.bbuf = null
        if (buf.length > config.preferredBufSize) reallocateBufToPreferredSize()
        if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
        bbuf.position(totalRead.toInt - tail + head + position)
      }
    }

  /**
    * Reads a JSON value from the given string into an instance of type `A` using the given [[JsonValueCodec]].
    *
    * @param codec the JSON value codec
    * @param s the string with the JSON input
    * @param config The reader configuration.
    * @tparam A the type of the value to read
    * @return an instance of type `A` containing the decoded JSON value.
    * @throws JsonReaderException in cases of reaching the end of input during parsing of JSON value or
    *                             unexpected format of JSON value or when configured checking of reaching the end of input
    *                             doesn't pass after reading of the whole JSON value
    */
  private[jsoniter_scala] def read[@sp A](codec: JsonValueCodec[A], s: String, config: ReaderConfig): A = {
    val currBuf = this.buf
    try {
      this.buf = s.getBytes(UTF_8)
      this.config = config
      head = 0
      val to = buf.length
      tail = to
      totalRead = 0
      mark = -1
      val x = codec.decodeValue(this, codec.nullValue)
      if (head != to && config.checkForEndOfInput) endOfInputOrError()
      x
    } finally {
      this.buf = currBuf
      if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
    }
  }

  /**
    * Scans JSON values separated by whitespaces from the given input stream and applies the given function to each decoded value until it returns `false`.
    *
    * @param codec the JSON value codec
    * @param in the input stream with the JSON input
    * @param config the reader configuration
    * @param f the callback function to apply to each decoded JSON value
    * @throws JsonReaderException in cases of reaching the end of input during parsing of JSON value or
    *                             unexpected format of JSON value
    */
  private[jsoniter_scala] def scanValueStream[@sp A](codec: JsonValueCodec[A], in: InputStream, config: ReaderConfig)
                                                    (f: A => Boolean): Unit =
    try {
      this.config = config
      this.in = in
      head = 0
      tail = 0
      totalRead = 0
      mark = -1
      if (buf.length < config.preferredBufSize) reallocateBufToPreferredSize()
      while (f(codec.decodeValue(this, codec.nullValue)) && skipWhitespaces()) ()
    } finally {
      this.in = null
      if (buf.length > config.preferredBufSize) reallocateBufToPreferredSize()
      if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
    }

  /**
    * Scans JSON array from the given input stream and applies the given function to each decoded value until it returns `false`.
    *
    * @param codec the JSON value codec
    * @param in the input stream with the JSON input
    * @param config the reader configuration
    * @param f the function to apply to each decoded JSON value
    * @throws JsonReaderException in cases of reaching the end of input during parsing of JSON value or
    *                             unexpected format of JSON value or when configured checking of reaching the end of input
    *                             doesn't pass after reading of the whole JSON array
    */
  private[jsoniter_scala] def scanArray[@sp A](codec: JsonValueCodec[A], in: InputStream, config: ReaderConfig)
                                              (f: A => Boolean): Unit =
    try {
      this.config = config
      this.in = in
      head = 0
      tail = 0
      totalRead = 0
      mark = -1
      if (buf.length < config.preferredBufSize) reallocateBufToPreferredSize()
      var continue = true
      if (isNextToken('[', head)) {
        if (!isNextToken(']', head)) {
          head -= 1
          while ({
            continue = f(codec.decodeValue(this, codec.nullValue))
            continue && isNextToken(',', head)
          }) ()
          if (continue && !isCurrentToken(']', head)) arrayEndOrCommaError()
        }
      } else readNullOrTokenError((), '[')
      if (continue && config.checkForEndOfInput) endOfInputOrError()
    } finally {
      this.in = null
      if (buf.length > config.preferredBufSize) reallocateBufToPreferredSize()
      if (charBuf.length > config.preferredCharBufSize) reallocateCharBufToPreferredSize()
    }

  /**
    * Skips whitespace characters and checks if there are non-whitespace characters left in the input.
    *
    * @throws JsonReaderException when there is at least one non-whitespace character left in the input
    */
  private[jsoniter_scala] def endOfInputOrError(): Unit =
    if (skipWhitespaces()) decodeError("expected end of input", head)

  /**
    * Skips whitespace characters in the input.
    *
    * @return `true` if and only if there are non-whitespace characters left in the input after skipping the whitespace
    */
  private[jsoniter_scala] def skipWhitespaces(): Boolean = {
    var pos = head
    var buf = this.buf
    while ((pos < tail || {
      pos = loadMore(pos)
      buf = this.buf
      pos < tail
    }) && {
      val b = buf(pos)
      b == ' ' || b == '\n' || (b | 0x4) == '\r'
    }) pos += 1
    head = pos
    pos != tail
  }

  private[this] def tokenOrDigitError(t: Byte, pos: Int = head - 1): Nothing = {
    var i = appendString("expected '", 0)
    i = appendChar(t.toChar, i)
    i = appendString("' or digit", i)
    decodeError(i, pos, null)
  }

  private[this] def tokensError(t1: Byte, t2: Byte, pos: Int = head - 1): Nothing = {
    var i = appendString("expected '", 0)
    i = appendChar(t1.toChar, i)
    i = appendString("' or '", i)
    i = appendChar(t2.toChar, i)
    i = appendChar('\'', i)
    decodeError(i, pos, null)
  }

  private[this] def tokenOrNullError(t: Byte, pos: Int = head - 1): Nothing = {
    var i = appendString("expected '", 0)
    i = appendChar(t.toChar, i)
    i = appendString("' or null", i)
    decodeError(i, pos, null)
  }

  private[this] def tokenError(t: Byte, pos: Int = head - 1): Nothing = {
    var i = appendString("expected '", 0)
    i = appendChar(t.toChar, i)
    i = appendChar('\'', i)
    decodeError(i, pos, null)
  }

  private[this] def decodeError(msg: String, pos: Int, cause: Throwable = null): Nothing =
    decodeError(appendString(msg, 0), pos, cause)

  private[this] def decodeError(from: Int, pos: Int, cause: Throwable): Nothing = {
    var i = appendString(", offset: 0x", from)
    val offset =
      if ((bbuf eq null) && (in eq null)) 0
      else totalRead - tail
    i = appendHexOffset(offset + pos, i)
    if (config.appendHexDumpToParseException) {
      i = appendString(", buf:", i)
      i = appendHexDump(pos, offset.toInt, i)
    }
    throw new JsonReaderException(new String(charBuf, 0, i), cause, config.throwReaderExceptionWithStackTrace)
  }

  @inline
  @tailrec
  private[this] def nextByte(pos: Int): Byte =
    if (pos < tail) {
      head = pos + 1
      buf(pos)
    } else nextByte(loadMoreOrError(pos))

  @inline
  @tailrec
  private[this] def nextByteOrError(t: Byte, pos: Int): Unit =
    if (pos < tail) {
      if (buf(pos) != t) tokenError(t, pos)
      head = pos + 1
    } else nextByteOrError(t, loadMoreOrError(pos))

  @tailrec
  private[this] def nextToken(pos: Int): Byte =
    if (pos < tail) {
      val b = buf(pos)
      if (b == ' ' || b == '\n' || (b | 0x4) == '\r') nextToken(pos + 1)
      else {
        head = pos + 1
        b
      }
    } else nextToken(loadMoreOrError(pos))

  @tailrec
  private[this] def nextTokenOrError(t: Byte, pos: Int): Unit =
    if (pos < tail) {
      val b = buf(pos)
      head = pos + 1
      if (b != t && ((b != ' ' && b != '\n' && (b | 0x4) != '\r') || nextToken(pos + 1) != t)) tokenError(t, head - 1)
    } else nextTokenOrError(t, loadMoreOrError(pos))

  @tailrec
  private[this] def isNextToken(t: Byte, pos: Int): Boolean =
    if (pos < tail) {
      val b = buf(pos)
      head = pos + 1
      b == t || ((b == ' ' || b == '\n' || (b | 0x4) == '\r') && nextToken(pos + 1) == t)
    } else isNextToken(t, loadMoreOrError(pos))

  @inline
  private[this] def isCurrentToken(t: Byte, pos: Int): Boolean = {
    if (pos == 0) illegalTokenOperation()
    buf(pos - 1) == t
  }

  private[this] def illegalTokenOperation(): Nothing =
    throw new IllegalStateException("expected preceding call of 'nextToken()' or 'isNextToken()'")

  private[this] def missingSetMarkOperation(): Nothing =
    throw new IllegalStateException("expected preceding call of 'setMark()'")

  @tailrec
  private[this] def parseYearWithByte(t: Byte, maxDigits: Int, pos: Int): Int =
    if (pos + 4 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      if (b1 >= '0' && b1 <= '9') {
        val b2 = buf(pos + 1)
        val b3 = buf(pos + 2)
        val b4 = buf(pos + 3)
        val b5 = buf(pos + 4)
        head = pos + 5
        if (b2 < '0' || b2 > '9') digitError(pos + 1)
        if (b3 < '0' || b3 > '9') digitError(pos + 2)
        if (b4 < '0' || b4 > '9') digitError(pos + 3)
        if (b5 != t) tokenError(t, pos + 4)
        b1 * 1000 + b2 * 100 + b3 * 10 + b4 - 53328 // 53328 == '0' * 1111
      } else parseNon4DigitYearWithByte(t, maxDigits, b1, pos)
    } else parseYearWithByte(t, maxDigits, loadMoreOrError(pos))

  private[this] def parseNon4DigitYearWithByte(t: Byte, maxDigits: Int, b1: Byte, p: Int): Int = {
    var pos = p
    var buf = this.buf
    val b2 = buf(pos + 1)
    val b3 = buf(pos + 2)
    val b4 = buf(pos + 3)
    val b5 = buf(pos + 4)
    if (b1 != '-' && b1 != '+') decodeError("expected '-' or '+' or digit", pos)
    if (b2 < '0' || b2 > '9') digitError(pos + 1)
    if (b3 < '0' || b3 > '9') digitError(pos + 2)
    if (b4 < '0' || b4 > '9') digitError(pos + 3)
    if (b5 < '0' || b5 > '9') digitError(pos + 4)
    var year = b2 * 1000 + b3 * 100 + b4 * 10 + b5 - 53328 // 53328 == '0' * 1111
    var yearDigits = 4
    var b: Byte = 0
    pos += 5
    while ({
      if (pos >= tail) {
        pos = loadMoreOrError(pos)
        buf = this.buf
      }
      b = buf(pos)
      (b >= '0' && b <= '9') && yearDigits < maxDigits
    }) {
      year =
        if (year > 100000000) 2147483647
        else year * 10 + (b - '0')
      yearDigits += 1
      pos += 1
    }
    head = pos + 1
    if (b1 == '-' && year == 0 || yearDigits == 10 && year > 1000000000) yearError(pos - 1)
    if (b != t) yearError(t, maxDigits, pos, b1, yearDigits)
    if (b1 == '-') year = -year
    if (year >= 0 && year < 10000) digitError(pos)
    year
  }

  @tailrec
  private[this] def parseMonthWithByte(t: Byte, pos: Int): Int =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      val month = b1 * 10 + b2 - 528 // 528 == '0' * 11
      head = pos + 3
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (month < 1 || month > 12) monthError(pos + 1)
      if (b3 != t) tokenError(t, pos + 2)
      month
    } else parseMonthWithByte(t, loadMoreOrError(pos))

  @tailrec
  private[this] def parseDayWithByte(year: Int, month: Int, t: Byte, pos: Int): Int =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      val day = b1 * 10 + b2 - 528 // 528 == '0' * 11
      head = pos + 3
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (day == 0 || (day > 28 && day > maxDayForYearMonth(year, month))) dayError(pos + 1)
      if (b3 != t) tokenError(t, pos + 2)
      day
    } else parseDayWithByte(year, month, t, loadMoreOrError(pos))

  @tailrec
  private[this] def parseHourWithColon(pos: Int): Int =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      val hour = b1 * 10 + b2 - 528 // 528 == '0' * 11
      head = pos + 3
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (hour > 23) hourError(pos + 1)
      if (b3 != ':') tokenError(':', pos + 2)
      hour
    } else parseHourWithColon(loadMoreOrError(pos))

  @tailrec
  private[this] def parseMinuteWithColon(pos: Int): Int =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      head = pos + 3
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (b1 > '5') minuteError(pos + 1)
      if (b3 != ':') tokenError(':', pos + 2)
      b1 * 10 + b2 - 528 // 528 == '0' * 11
    } else parseMinuteWithColon(loadMoreOrError(pos))

  @tailrec
  private[this] def parseMinute(pos: Int): Int =
    if (pos + 1 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      head = pos + 2
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (b1 > '5') minuteError(pos + 1)
      b1 * 10 + b2 - 528 // 528 == '0' * 11
    } else parseMinute(loadMoreOrError(pos))

  @tailrec
  private[this] def parseSecond(pos: Int): Int =
    if (pos + 1 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      head = pos + 2
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (b1 > '5') secondError(pos + 1)
      b1 * 10 + b2 - 528 // 528 == '0' * 11
    } else parseSecond(loadMoreOrError(pos))

  private[this] def parseOptionalNanoWithDoubleQuotes(): Int = {
    var b = nextByte(head)
    var nano = 0
    if (b == '.') {
      var pos = head
      var buf = this.buf
      var nanoDigitWeight = 100000000
      while ({
        if (pos >= tail) {
          pos = loadMoreOrError(pos)
          buf = this.buf
        }
        b = buf(pos)
        pos += 1
        (b >= '0' && b <= '9') && nanoDigitWeight != 0
      }) {
        nano += (b - '0') * nanoDigitWeight
        nanoDigitWeight /= 10
      }
      head = pos
      if (b != '"') nanoError(nanoDigitWeight, '"', pos - 1)
    } else if (b != '"') tokensError('.', '"')
    nano
  }

  @tailrec
  private[this] def parseOffsetHour(pos: Int): Int =
    if (pos + 1 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val offsetHour = b1 * 10 + b2 - 528 // 528 == '0' * 11
      head = pos + 2
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (offsetHour > 18) timezoneOffsetHourError(pos + 1)
      offsetHour
    } else parseOffsetHour(loadMoreOrError(pos))

  @tailrec
  private[this] def parseOffsetMinute(pos: Int): Int =
    if (pos + 1 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      head = pos + 2
      if (b1 == '0' && b2 == '0') 0
      else {
        if (b1 < '0' || b1 > '9') digitError(pos)
        if (b2 < '0' || b2 > '9') digitError(pos + 1)
        if (b1 > '5') timezoneOffsetMinuteError(pos + 1)
        b1 * 10 + b2 - 528 // 528 == '0' * 11
      }
    } else parseOffsetMinute(loadMoreOrError(pos))

  @tailrec
  private[this] def parseOffsetSecond(pos: Int): Int =
    if (pos + 1 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      head = pos + 2
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (b1 > '5') timezoneOffsetSecondError(pos + 1)
      b1 * 10 + b2 - 528 // 528 == '0' * 11
    } else parseOffsetSecond(loadMoreOrError(pos))

  @tailrec
  private[this] def parseOffsetSecondWithDoubleQuotes(pos: Int): Int =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      head = pos + 3
      if (b1 < '0' || b1 > '9') digitError(pos)
      if (b2 < '0' || b2 > '9') digitError(pos + 1)
      if (b1 > '5') timezoneOffsetSecondError(pos + 1)
      if (b3 != '"') tokenError('"', pos + 2)
      b1 * 10 + b2 - 528 // 528 == '0' * 11
    } else parseOffsetSecondWithDoubleQuotes(loadMoreOrError(pos))

  private[this] def parseZoneId(): ZoneId = {
    var pos = head
    var buf = this.buf
    var from = pos
    val oldMark = mark
    val newMark =
      if (oldMark < 0) from
      else oldMark
    mark = newMark
    var hash = 0
    var b: Byte = 0
    while ({
      if (pos >= tail) {
        pos = loadMoreOrError(pos)
        buf = this.buf
      }
      b = buf(pos)
      pos += 1
      b != '"'
    }) hash = (hash << 5) - hash + b
    if (mark == 0) from -= newMark
    if (mark > oldMark) mark = oldMark
    var k = zoneIdKey
    if (k eq null) {
      k = new Key
      zoneIdKey = k
    }
    k.set(hash, buf, from, pos - 1)
    var zoneId = zoneIds.get(k)
    if (zoneId eq null) zoneId = toZoneId(k, pos)
    head = pos
    zoneId
  }

  @tailrec
  private[this] def parseNullOrError[@sp A](default: A, error: String, pos: Int): A =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      head = pos + 3
      if (b1 != 'u') decodeError(error, pos)
      if (b2 != 'l') decodeError(error, pos + 1)
      if (b3 != 'l') decodeError(error, pos + 2)
      default
    } else parseNullOrError(default, error, loadMoreOrError(pos))

  @tailrec
  private[this] def parseNullOrTokenError[@sp A](default: A, t: Byte, pos: Int): A =
    if (pos + 2 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      val b2 = buf(pos + 1)
      val b3 = buf(pos + 2)
      head = pos + 3
      if (b1 != 'u') tokenOrNullError(t, pos)
      if (b2 != 'l') tokenOrNullError(t, pos + 1)
      if (b3 != 'l') tokenOrNullError(t, pos + 2)
      default
    } else parseNullOrTokenError(default, t, loadMoreOrError(pos))

  private[this] def appendChar(ch: Char, i: Int): Int = {
    ensureCharBufCapacity(i + 1)
    charBuf(i) = ch
    i + 1
  }

  private[this] def appendChars(cs: Array[Char], i: Int): Int = {
    val len = cs.length
    val required = i + len
    ensureCharBufCapacity(required)
    System.arraycopy(cs, 0, charBuf, i, len)
    required
  }

  private[this] def appendString(s: String, i: Int): Int = {
    val len = s.length
    val required = i + len
    ensureCharBufCapacity(required)
    s.getChars(0, len, charBuf, i)
    required
  }

  private[this] def prependString(s: String, i: Int): Int = {
    val len = s.length
    val required = i + len
    ensureCharBufCapacity(required)
    var i1 = required - 1
    var i2 = i1 - len
    while (i2 >= 0) {
      charBuf(i1) = charBuf(i2)
      i1 -= 1
      i2 -= 1
    }
    s.getChars(0, len, charBuf, 0)
    required
  }

  @tailrec
  private[this] def parseBoolean(isToken: Boolean, pos: Int): Boolean =
    if (pos + 3 < tail) {
      val buf = this.buf
      val b1 = buf(pos)
      if (b1 == 't') {
        val b2 = buf(pos + 1)
        val b3 = buf(pos + 2)
        val b4 = buf(pos + 3)
        head = pos + 4
        if (b2 != 'r') booleanError(pos + 1)
        if (b3 != 'u') booleanError(pos + 2)
        if (b4 != 'e') booleanError(pos + 3)
        true
      } else if (b1 == 'f') {
        if (pos + 4 < tail) {
          val b2 = buf(pos + 1)
          val b3 = buf(pos + 2)
          val b4 = buf(pos + 3)
          val b5 = buf(pos + 4)
          head = pos + 5
          if (b2 != 'a') booleanError(pos + 1)
          if (b3 != 'l') booleanError(pos + 2)
          if (b4 != 's') booleanError(pos + 3)
          if (b5 != 'e') booleanError(pos + 4)
          false
        } else parseBoolean(isToken, loadMoreOrError(pos))
      } else if (isToken && (b1 == ' ' || b1 == '\n' || (b1 | 0x4) == '\r')) parseBoolean(isToken, pos + 1)
      else booleanError(pos)
    } else parseBoolean(isToken, loadMoreOrError(pos))

  private[this] def booleanError(pos: Int): Nothing = decodeError("illegal boolean", pos)

  def readByte(isToken: Boolean): Byte = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    var s = 0
    if (b == '-') {
      b = nextByte(head)
      s = -1
    }
    if (b < '0' || b > '9') numberError()
    var x = b - '0'
    if (isToken && x == 0) ensureNotLeadingZero()
    else {
      var pos = head
      var buf = this.buf
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        x = x * 10 + (b - '0')
        if (x > 128) byteOverflowError(pos)
        pos += 1
      }
      head = pos
      x = (x ^ s) - s
      if (x == 128) byteOverflowError(pos - 1)
      if ((b | 0x20) == 'e' || b == '.') numberError(pos)
    }
    x.toByte
  }

  def readShort(isToken: Boolean): Short = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    var s = 0
    if (b == '-') {
      b = nextByte(head)
      s = -1
    }
    if (b < '0' || b > '9') numberError()
    var x = b - '0'
    if (isToken && x == 0) ensureNotLeadingZero()
    else {
      var pos = head
      var buf = this.buf
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        x = x * 10 + (b - '0')
        if (x > 32768) shortOverflowError(pos)
        pos += 1
      }
      head = pos
      x = (x ^ s) - s
      if (x == 32768) shortOverflowError(pos - 1)
      if ((b | 0x20) == 'e' || b == '.') numberError(pos)
    }
    x.toShort
  }

  def readInt(isToken: Boolean): Int = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    var s = -1
    if (b == '-') {
      b = nextByte(head)
      s = 0
    }
    if (b < '0' || b > '9') numberError()
    var x = '0' - b
    if (isToken && x == 0) ensureNotLeadingZero()
    else {
      var pos = head
      var buf = this.buf
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (x < -214748364 || {
          x = x * 10 + ('0' - b)
          x > 0
        }) intOverflowError(pos)
        pos += 1
      }
      head = pos
      x = (x ^ s) - s
      if ((s & x) == -2147483648) intOverflowError(pos - 1)
      if ((b | 0x20) == 'e' || b == '.') numberError(pos)
    }
    x
  }

  def readLong(isToken: Boolean): Long = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    var isPos = true
    if (b == '-') {
      b = nextByte(head)
      isPos = false
    }
    if (b < '0' || b > '9') numberError()
    var x1 = '0' - b
    if (isToken && x1 == 0) {
      ensureNotLeadingZero()
      0L
    } else {
      var pos = head
      var buf = this.buf
      while (x1 > -214748364 && (pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        x1 = x1 * 10 + ('0' - b)
        pos += 1
      }
      var x = x1.toLong
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (x < -922337203685477580L || {
          x = (x << 3) + (x << 1) + ('0' - b)
          x > 0L
        }) longOverflowError(pos)
        pos += 1
      }
      head = pos
      if (isPos) {
        if (x == -9223372036854775808L) longOverflowError(pos - 1)
        x = -x
      }
      if ((b | 0x20) == 'e' || b == '.') numberError(pos)
      x
    }
  }

  private[this] def ensureNotLeadingZero(): Unit = {
    var pos = head
    if ((pos < tail || {
      pos = loadMore(pos)
      pos < tail
    }) && {
      val b = buf(pos)
      b >= '0' && b <= '9'
    }) leadingZeroError(pos - 1)
  }

  def readDouble(isToken: Boolean): Double = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    var isNeg = false
    if (b == '-') {
      b = nextByte(head)
      isNeg = true
    }
    if (b < '0' || b > '9') numberError()
    var pos = head
    var buf = this.buf
    val from = pos - 1
    val oldMark = mark
    val newMark =
      if (oldMark < 0) from
      else oldMark
    mark = newMark
    var m10 = (b - '0').toLong
    var e10 = 0
    var digits = 1
    if (isToken && b == '0') {
      if ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) leadingZeroError(pos - 1)
    } else {
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (m10 < 922337203685477580L) {
          m10 = (m10 << 3) + (m10 << 1) + (b - '0')
          digits += 1
        } else e10 += 1
        pos += 1
      }
    }
    if (b == '.') {
      pos += 1
      e10 += digits
      var noFracDigits = true
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (m10 < 922337203685477580L) {
          m10 = (m10 << 3) + (m10 << 1) + (b - '0')
          digits += 1
        }
        noFracDigits = false
        pos += 1
      }
      e10 -= digits
      if (noFracDigits) numberError(pos)
    }
    if ((b | 0x20) == 'e') {
      b = nextByte(pos + 1)
      val sb = b
      if (b == '-' || b == '+') b = nextByte(head)
      if (b < '0' || b > '9') numberError()
      var exp = b - '0'
      pos = head
      buf = this.buf
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (exp < 214748364) exp = exp * 10 + (b - '0')
        pos += 1
      }
      if (sb == '-') exp = -exp
      e10 += exp
    }
    head = pos
    var x: Double =
      if (e10 == 0 && m10 < 922337203685477580L) m10.toDouble
      else if (m10 < 4503599627370496L && e10 >= -22 && e10 <= 38 - digits) {
        val pow10 = pow10Doubles
        if (e10 < 0) m10 / pow10(-e10)
        else if (e10 <= 22) m10 * pow10(e10)
        else {
          val slop = 16 - digits
          (m10 * pow10(slop)) * pow10(e10 - slop)
        }
      } else toDouble(m10, e10, from, newMark, pos)
    if (isNeg) x = -x
    if (mark > oldMark) mark = oldMark
    x
  }

  // Based on the 'Moderate Path' algorithm from the awesome library of Alexander Huszagh: https://github.com/Alexhuszagh/rust-lexical
  // Here is his inspiring post: https://www.reddit.com/r/rust/comments/a6j5j1/making_rust_float_parsing_fast_and_correct
  @inline
  private[this] def toDouble(m10: Long, e10: Int, from: Int, newMark: Int, pos: Int): Double =
    if (m10 == 0L || e10 < -343) 0.0
    else if (e10 >= 310) Double.PositiveInfinity
    else {
      var shift = java.lang.Long.numberOfLeadingZeros(m10)
      var m2 = unsignedMultiplyHigh(pow10Mantissas(e10 + 343), m10 << shift) // FIXME: Use Math.unsignedMultiplyHigh after dropping of JDK 17 support
      var e2 = (e10 * 108853 >> 15) - shift + 1 // (e10 * Math.log(10) / Math.log(2)).toInt - shift + 1
      shift = java.lang.Long.numberOfLeadingZeros(m2)
      m2 <<= shift
      e2 -= shift
      val roundingError =
        (if (m10 < 922337203685477580L) 1
        else 19) << shift
      val truncatedBitNum = Math.max(-1074 - e2, 11)
      val savedBitNum = 64 - truncatedBitNum
      val mask = -1L >>> Math.max(savedBitNum, 0)
      val halfwayDiff = (m2 & mask) - (mask >>> 1)
      if (Math.abs(halfwayDiff) > roundingError || savedBitNum <= 0) java.lang.Double.longBitsToDouble {
        if (savedBitNum <= 0) m2 = 0L
        m2 >>>= truncatedBitNum
        e2 += truncatedBitNum
        if (savedBitNum >= 0 && halfwayDiff > 0L) {
          if (m2 == 0x1FFFFFFFFFFFFFL) {
            m2 = 0x10000000000000L
            e2 += 1
          } else m2 += 1L
        }
        if (e2 == -1074) m2
        else if (e2 >= 972) 0x7FF0000000000000L
        else (e2 + 1075).toLong << 52 | m2 & 0xFFFFFFFFFFFFFL
      } else toDouble(from, newMark, pos)
    }

  private[this] def toDouble(from: Int, newMark: Int, pos: Int): Double = {
    var offset = from
    if (mark == 0) offset -= newMark
    java.lang.Double.parseDouble(new String(buf, offset, pos - offset))
  }

  def readFloat(isToken: Boolean): Float = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    var isNeg = false
    if (b == '-') {
      b = nextByte(head)
      isNeg = true
    }
    if (b < '0' || b > '9') numberError()
    var pos = head
    var buf = this.buf
    val from = pos - 1
    val oldMark = mark
    val newMark =
      if (oldMark < 0) from
      else oldMark
    mark = newMark
    var m10 = (b - '0').toLong
    var e10 = 0
    var digits = 1
    if (isToken && b == '0') {
      if ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) leadingZeroError(pos - 1)
    } else {
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (m10 < 922337203685477580L) {
          m10 = (m10 << 3) + (m10 << 1) + (b - '0')
          digits += 1
        } else e10 += 1
        pos += 1
      }
    }
    if (b == '.') {
      pos += 1
      e10 += digits
      var noFracDigits = true
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (m10 < 922337203685477580L) {
          m10 = (m10 << 3) + (m10 << 1) + (b - '0')
          digits += 1
        }
        noFracDigits = false
        pos += 1
      }
      e10 -= digits
      if (noFracDigits) numberError(pos)
    }
    if ((b | 0x20) == 'e') {
      b = nextByte(pos + 1)
      val sb = b
      if (b == '-' || b == '+') b = nextByte(head)
      if (b < '0' || b > '9') numberError()
      var exp = b - '0'
      pos = head
      buf = this.buf
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (exp < 214748364) exp = exp * 10 + (b - '0')
        pos += 1
      }
      if (sb == '-') exp = -exp
      e10 += exp
    }
    head = pos
    var x: Float =
      if (e10 == 0 && m10 < 922337203685477580L) m10.toFloat
      else if (m10 < 4294967296L && e10 >= digits - 23 && e10 <= 19 - digits) {
        val pow10 = pow10Doubles
        (if (e10 < 0) m10 / pow10(-e10)
        else m10 * pow10(e10)).toFloat
      } else toFloat(m10, e10, from, newMark, pos)
    if (isNeg) x = -x
    if (mark > oldMark) mark = oldMark
    x
  }

  // Based on the 'Moderate Path' algorithm from the awesome library of Alexander Huszagh: https://github.com/Alexhuszagh/rust-lexical
  // Here is his inspiring post: https://www.reddit.com/r/rust/comments/a6j5j1/making_rust_float_parsing_fast_and_correct
  @inline
  private[this] def toFloat(m10: Long, e10: Int, from: Int, newMark: Int, pos: Int): Float =
    if (m10 == 0L || e10 < -64) 0.0f
    else if (e10 >= 39) Float.PositiveInfinity
    else {
      var shift = java.lang.Long.numberOfLeadingZeros(m10)
      var m2 = unsignedMultiplyHigh(pow10Mantissas(e10 + 343), m10 << shift) // FIXME: Use Math.unsignedMultiplyHigh after dropping of JDK 17 support
      var e2 = (e10 * 108853 >> 15) - shift + 1 // (e10 * Math.log(10) / Math.log(2)).toInt - shift + 1
      shift = java.lang.Long.numberOfLeadingZeros(m2)
      m2 <<= shift
      e2 -= shift
      val roundingError =
        (if (m10 < 922337203685477580L) 1
        else 19) << shift
      val truncatedBitNum = Math.max(-149 - e2, 40)
      val savedBitNum = 64 - truncatedBitNum
      val mask = -1L >>> Math.max(savedBitNum, 0)
      val halfwayDiff = (m2 & mask) - (mask >>> 1)
      if (Math.abs(halfwayDiff) > roundingError || savedBitNum <= 0) java.lang.Float.intBitsToFloat {
        var mf = 0
        if (savedBitNum > 0) mf = (m2 >>> truncatedBitNum).toInt
        e2 += truncatedBitNum
        if (savedBitNum >= 0 && halfwayDiff > 0L) {
          if (mf == 0xFFFFFF) {
            mf = 0x800000
            e2 += 1
          } else mf += 1
        }
        if (e2 == -149) mf
        else if (e2 >= 105) 0x7F800000
        else e2 + 150 << 23 | mf & 0x7FFFFF
      } else toFloat(from, newMark, pos)
    }

  private[this] def toFloat(from: Int, newMark: Int, pos: Int): Float = {
    var offset = from
    if (mark == 0) offset -= newMark
    java.lang.Float.parseFloat(new String(buf, offset, pos - offset))
  }

  // 64-bit unsigned multiplication was adopted from the great Hacker's Delight function
  // (Henry S. Warren, Hacker's Delight, Addison-Wesley, 2nd edition, Fig. 8.2)
  // https://doc.lagout.org/security/Hackers%20Delight.pdf
  @inline
  private[this] def unsignedMultiplyHigh(x: Long, y: Long): Long = {
    val xl = x & 0xFFFFFFFFL
    val xh = x >>> 32
    val yl = y & 0xFFFFFFFFL
    val yh = y >>> 32
    val t = xh * yl + (xl * yl >>> 32)
    xh * yh + (t >>> 32) + (xl * yh + (t & 0xFFFFFFFFL) >>> 32)
  }

  def readBigInt(isToken: Boolean, default: BigInt, digitsLimit: Int): BigInt = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    if (isToken && b == 'n') readNullOrNumberError(default, head)
    else {
      var s = 0
      if (b == '-') {
        b = nextByte(head)
        s = -1
      }
      if (b < '0' || b > '9') numberError()
      if (isToken && b == '0') {
        ensureNotLeadingZero()
        BigInt(0)
      } else {
        var pos = head
        var buf = this.buf
        var from = pos - 1
        val oldMark = mark
        val newMark =
          if (oldMark < 0) from
          else oldMark
        mark = newMark
        while ((pos < tail || {
          pos = loadMore(pos)
          buf = this.buf
          pos < tail
        }) && {
          b = buf(pos)
          b >= '0' && b <= '9'
        }) pos += 1
        head = pos
        if (mark == 0) from -= newMark
        if (mark > oldMark) mark = oldMark
        val len = pos - from
        if (len >= digitsLimit) digitsLimitError(from + digitsLimit - 1)
        if ((b | 0x20) == 'e' || b == '.') numberError(pos)
        if (len < 19) {
          var x = buf(from) - '0'
          from += 1
          val limit = Math.min(from + 8, pos)
          while (from < limit) {
            x = x * 10 + (buf(from) - '0')
            from += 1
          }
          var x1 = x.toLong
          while (from < pos) {
            x1 = (x1 << 3) + (x1 << 1) + (buf(from) - '0')
            from += 1
          }
          if (s != 0) x1 = -x1
          BigInt(x1)
        } else new BigInt({
          if (len <= 36) toBigDecimal36(buf, from, pos, s, 0).unscaledValue
          else if (len <= 308) toBigInteger308(buf, from, pos, s)
          else {
            // Based on the great idea of Eric Obermühlner to use a tree of smaller BigDecimals for parsing huge numbers
            // with O(n^1.5) complexity instead of O(n^2) when using the constructor for the decimal representation from JDK:
            // https://github.com/eobermuhlner/big-math/commit/7a5419aac8b2adba2aa700ccf00197f97b2ad89f
            val mid = len >> 1
            val midPos = pos - mid
            toBigDecimal(buf, from, midPos, s, -mid).add(toBigDecimal(buf, midPos, pos, s, 0)).unscaledValue
          }
        })
      }
    }
  }

  def readBigDecimal(isToken: Boolean, default: BigDecimal, mc: MathContext, scaleLimit: Int,
                     digitsLimit: Int): BigDecimal = {
    var b =
      if (isToken) nextToken(head)
      else nextByte(head)
    if (isToken && b == 'n') readNullOrNumberError(default, head)
    else {
      var s = 0
      if (b == '-') {
        b = nextByte(head)
        s = -1
      }
      if (b < '0' || b > '9') numberError()
      var pos = head
      var buf = this.buf
      var from = pos - 1
      val oldMark = mark
      val newMark =
        if (oldMark < 0) from
        else oldMark
      mark = newMark
      var digits = 1
      if (isToken && b == '0') {
        if ((pos < tail || {
          pos = loadMore(pos)
          buf = this.buf
          pos < tail
        }) && {
          b = buf(pos)
          b >= '0' && b <= '9'
        }) leadingZeroError(pos - 1)
      } else {
        digits -= pos
        while ((pos < tail || {
          digits += pos
          pos = loadMore(pos)
          digits -= pos
          buf = this.buf
          pos < tail
        }) && {
          b = buf(pos)
          b >= '0' && b <= '9'
        }) pos += 1
        digits += pos
      }
      var fracLen, scale = 0
      if (digits >= digitsLimit) digitsLimitError(pos + digitsLimit - digits - 1)
      if (b == '.') {
        pos += 1
        fracLen -= pos
        while ((pos < tail || {
          fracLen += pos
          pos = loadMore(pos)
          fracLen -= pos
          buf = this.buf
          pos < tail
        }) && {
          b = buf(pos)
          b >= '0' && b <= '9'
        }) pos += 1
        fracLen += pos
        digits += fracLen
        if (fracLen == 0) numberError(pos)
        if (digits >= digitsLimit) digitsLimitError(pos + digitsLimit - digits - 1)
      }
      if ((b | 0x20) == 'e') {
        b = nextByte(pos + 1)
        val sb = b
        if (b == '-' || b == '+') b = nextByte(head)
        if (b < '0' || b > '9') numberError()
        scale = '0' - b
        pos = head
        buf = this.buf
        while ((pos < tail || {
          pos = loadMore(pos)
          buf = this.buf
          pos < tail
        }) && {
          b = buf(pos)
          b >= '0' && b <= '9'
        }) {
          if (scale < -214748364 || {
            scale = scale * 10 + ('0' - b)
            scale > 0
          }) numberError(pos)
          pos += 1
        }
        if (sb == '-') scale = -scale
        if (scale == -2147483648) numberError(pos - 1)
      }
      head = pos
      if (mark == 0) from -= newMark
      if (mark > oldMark) mark = oldMark
      var d =
        if (fracLen != 0) {
          val limit = from + digits + 1
          val fracPos = limit - fracLen
          val fracLimit = fracPos - 1
          if (digits < 10) {
            var x = buf(from) - '0'
            from += 1
            while (from < fracLimit) {
              x = x * 10 + (buf(from) - '0')
              from += 1
            }
            from += 1
            while (from < limit) {
              x = x * 10 + (buf(from) - '0')
              from += 1
            }
            java.math.BigDecimal.valueOf(((x ^ s) - s).toLong, scale + fracLen)
          } else if (digits < 19) {
            var x = (buf(from) - '0').toLong
            from += 1
            while (from < fracLimit) {
              x = (x << 3) + (x << 1) + (buf(from) - '0')
              from += 1
            }
            from += 1
            while (from < limit) {
              x = (x << 3) + (x << 1) + (buf(from) - '0')
              from += 1
            }
            if (s != 0) x = -x
            java.math.BigDecimal.valueOf(x, scale + fracLen)
          } else toBigDecimal(buf, from, fracLimit, s, scale).add(toBigDecimal(buf, fracPos, limit, s, scale + fracLen))
        } else toBigDecimal(buf, from, from + digits, s, scale)
      if (mc.getPrecision < digits) d = d.plus(mc)
      if (Math.abs(d.scale) >= scaleLimit) scaleLimitError()
      new BigDecimal(d, mc)
    }
  }

  private[this] def toBigDecimal(buf: Array[Byte], p: Int, limit: Int, s: Int, scale: Int): java.math.BigDecimal = {
    val len = limit - p
    if (len < 19) {
      var pos = p
      var x = buf(pos) - '0'
      pos += 1
      val limit1 = Math.min(pos + 8, limit)
      while (pos < limit1) {
        x = x * 10 + (buf(pos) - '0')
        pos += 1
      }
      var x1 = x.toLong
      while (pos < limit) {
        x1 = (x1 << 3) + (x1 << 1) + (buf(pos) - '0')
        pos += 1
      }
      if (s != 0) x1 = -x1
      java.math.BigDecimal.valueOf(x1, scale)
    } else if (len <= 36) toBigDecimal36(buf, p, limit, s, scale)
    else if (len <= 308) new java.math.BigDecimal(toBigInteger308(buf, p, limit, s), scale)
    else {
      // Based on the great idea of Eric Obermühlner to use a tree of smaller BigDecimals for parsing really big numbers
      // with O(n^1.5) complexity instead of O(n^2) when using the constructor for the decimal representation from JDK:
      // https://github.com/eobermuhlner/big-math/commit/7a5419aac8b2adba2aa700ccf00197f97b2ad89f
      val mid = len >> 1
      val midPos = limit - mid
      toBigDecimal(buf, p, midPos, s, scale - mid).add(toBigDecimal(buf, midPos, limit, s, scale))
    }
  }

  private[this] def toBigDecimal36(buf: Array[Byte], p: Int, limit: Int, s: Int, scale: Int): java.math.BigDecimal = {
    var pos = p
    var x = buf(pos) - '0'
    pos += 1
    val limit2 = limit - 18
    val limit1 = Math.min(pos + 8, limit2)
    while (pos < limit1) {
      x = x * 10 + (buf(pos) - '0')
      pos += 1
    }
    var x1 = x.toLong
    while (pos < limit2) {
      x1 = (x1 << 3) + (x1 << 1) + (buf(pos) - '0')
      pos += 1
    }
    var x2 =
      ((buf(pos) * 10 + buf(pos + 1) - 528) * 10000000 + // 528 == '0' * 11
        ((buf(pos + 2) * 10 + buf(pos + 3)) * 100000 +
          (buf(pos + 4) * 10 + buf(pos + 5)) * 1000 +
          (buf(pos + 6) * 10 + buf(pos + 7)) * 10 +
          buf(pos + 8) - 53333328)) * 1000000000L + // 53333328 == '0' * 1111111
        ((buf(pos + 9) * 10 + buf(pos + 10) - 528) * 10000000 + // 528 == '0' * 11
          ((buf(pos + 11) * 10 + buf(pos + 12)) * 100000 +
            (buf(pos + 13) * 10 + buf(pos + 14)) * 1000 +
            (buf(pos + 15) * 10 + buf(pos + 16)) * 10 +
            buf(pos + 17) - 53333328)) // 53333328 == '0' * 1111111
    if (s != 0) {
      x1 = -x1
      x2 = -x2
    }
    java.math.BigDecimal.valueOf(x1, scale - 18).add(java.math.BigDecimal.valueOf(x2, scale))
  }

  private[this] def toBigInteger308(buf: Array[Byte], p: Int, limit: Int, s: Int): java.math.BigInteger = {
    val len = limit - p
    val last = (len * 0.10381025296523008).toInt // (len * Math.log(10) / Math.log(1L << 32)).toInt
    var magnitude = this.magnitude
    if (magnitude eq null) {
      magnitude = new Array[Int](32)
      this.magnitude = magnitude
    } else {
      var i = 0
      while (i < last) {
        magnitude(i) = 0
        i += 1
      }
    }
    var x1 = 0
    val limit1 = len % 9 + p
    var pos = p
    while (pos < limit1) {
      x1 = x1 * 10 + (buf(pos) - '0')
      pos += 1
    }
    magnitude(last) = x1
    var first = last
    while (pos < limit) {
      var x =
        ((buf(pos) * 10 + buf(pos + 1) - 528) * 10000000 + // 528 == '0' * 11
          ((buf(pos + 2) * 10 + buf(pos + 3)) * 100000 +
            (buf(pos + 4) * 10 + buf(pos + 5)) * 1000 +
            (buf(pos + 6) * 10 + buf(pos + 7)) * 10 +
            buf(pos + 8) - 53333328)).toLong // 53333328 == '0' * 1111111
      pos += 9
      first = Math.max(first - 1, 0)
      var i = last
      while ({
        x += (magnitude(i) & 0xFFFFFFFFL) * 1000000000L
        magnitude(i) = x.toInt
        i -= 1
        i >= first
      }) {
        x >>>= 32
      }
    }
    val bs = new Array[Byte](last + 1 << 2)
    var i = 0
    while (i <= last) {
      val w = magnitude(i)
      val j = i << 2
      bs(j) = (w >> 24).toByte
      bs(j + 1) = (w >> 16).toByte
      bs(j + 2) = (w >> 8).toByte
      bs(j + 3) = w.toByte
      i += 1
    }
    new java.math.BigInteger(s | 1, bs)
  }

  private[this] def readNullOrNumberError[@sp A](default: A, pos: Int): A =
    if (default != null) parseNullOrError(default, "expected number or null", pos)
    else numberError(pos - 1)

  private[this] def numberError(pos: Int = head - 1): Nothing = decodeError("illegal number", pos)

  private[this] def digitsLimitError(pos: Int): Nothing = decodeError("value exceeds limit for number of digits", pos)

  private[this] def scaleLimitError(pos: Int = head - 1): Nothing = decodeError("value exceeds limit for scale", pos)

  private[this] def leadingZeroError(pos: Int): Nothing = decodeError("illegal number with leading zero", pos)

  private[this] def byteOverflowError(pos: Int): Nothing = decodeError("value is too large for byte", pos)

  private[this] def shortOverflowError(pos: Int): Nothing = decodeError("value is too large for short", pos)

  private[this] def intOverflowError(pos: Int): Nothing = decodeError("value is too large for int", pos)

  private[this] def longOverflowError(pos: Int): Nothing = decodeError("value is too large for long", pos)

  private[this] def parseDuration(): Duration = {
    var b = nextByte(head)
    var s = 0
    if (b == '-') {
      b = nextByte(head)
      s = -1
    }
    if (b != 'P') durationOrPeriodStartError(s)
    b = nextByte(head)
    var state = -1
    if (b == 'T') {
      b = nextByte(head)
      state = 0
    }
    var seconds = 0L
    var nano = 0
    while ({
      var sx = s
      if (b == '-') {
        b = nextByte(head)
        sx = ~sx
      }
      if (b < '0' || b > '9') durationOrPeriodDigitError(s, sx, state)
      var x1 = '0' - b
      var pos = head
      var buf = this.buf
      while (x1 > -214748364 && (pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        x1 = x1 * 10 + ('0' - b)
        pos += 1
      }
      var x = x1.toLong
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (x < -922337203685477580L || {
          x = (x << 3) + (x << 1) + ('0' - b)
          x > 0
        }) durationError(pos)
        pos += 1
      }
      if (sx == 0) {
        if (x == -9223372036854775808L) durationError(pos)
        x = -x
      }
      if (b == 'D' && state < 0) {
        if (x < -106751991167300L || x > 106751991167300L) durationError(pos) // -106751991167300L == Long.MinValue / 86400
        seconds = x * 86400
        state = 0
      } else if (b == 'H' && state <= 0) {
        if (x < -2562047788015215L || x > 2562047788015215L) durationError(pos) // -2562047788015215L == Long.MinValue / 3600
        seconds = sumSeconds((x << 12) - (x << 9) + (x << 4), seconds, pos)
        state = 1
      } else if (b == 'M' && state <= 1) {
        if (x < -153722867280912930L || x > 153722867280912930L) durationError(pos) // -153722867280912930L == Long.MinValue / 60
        seconds = sumSeconds(((x << 4) - x) << 2, seconds, pos)
        state = 2
      } else if (b == 'S' || b == '.') {
        seconds = sumSeconds(x, seconds, pos)
        state = 3
        if (b == '.') {
          pos += 1
          var nanoDigitWeight = 100000000
          while ({
            if (pos >= tail) {
              pos = loadMoreOrError(pos)
              buf = this.buf
            }
            b = buf(pos)
            (b >= '0' && b <= '9') && nanoDigitWeight != 0
          }) {
            nano += (b - '0') * nanoDigitWeight
            nanoDigitWeight /= 10
            pos += 1
          }
          if (b != 'S') nanoError(nanoDigitWeight, 'S', pos)
          nano = (nano ^ sx) - sx
        }
      } else durationError(state, pos)
      b = nextByte(pos + 1)
      b != '"'
    }) {
      if (state == 0) {
        if (b != 'T') tokensError('T', '"')
        b = nextByte(head)
      } else if (state == 3) tokenError('"')
    }
    if (nano == 0) Duration.ofSeconds(seconds)
    else Duration.ofSeconds(seconds, nano.toLong)
  }

  @inline
  private[this] def sumSeconds(s1: Long, s2: Long, pos: Int): Long = {
    val s = s1 + s2
    if (((s1 ^ s) & (s2 ^ s)) < 0) durationError(pos)
    s
  }

  private[this] def parseInstant(): Instant = {
    val year = parseYearWithByte('-', 10, head)
    val month = parseMonthWithByte('-', head)
    val day = parseDayWithByte(year, month, 'T', head)
    var epochSecond = epochDay(year, month, day) * 86400 + parseSecondOfDay(head) // 86400 == seconds per day
    var b = nextByte(head)
    var nano = 0
    var nanoDigitWeight = -2
    if (b == '.') {
      nanoDigitWeight = 100000000
      var pos = head
      var buf = this.buf
      while ({
        if (pos >= tail) {
          pos = loadMoreOrError(pos)
          buf = this.buf
        }
        b = buf(pos)
        pos += 1
        (b >= '0' && b <= '9') && nanoDigitWeight != 0
      }) {
        nano += (b - '0') * nanoDigitWeight
        nanoDigitWeight /= 10
      }
      head = pos
    }
    if (b == 'Z') nextByteOrError('"', head)
    else if (b == '-') epochSecond += parseOffsetTotalWithDoubleQuotes(head)
    else if (b == '+') epochSecond -= parseOffsetTotalWithDoubleQuotes(head)
    else timeError(nanoDigitWeight)
    if (nano == 0) Instant.ofEpochSecond(epochSecond)
    else Instant.ofEpochSecond(epochSecond, nano.toLong)
  }

  @inline
  private[this] def parseSecondOfDay(pos: Int): Long =
    (parseHourWithColon(pos) * 3600 + parseMinuteWithColon(head) * 60 + parseSecond(head)).toLong

  private[this] def parseLocalDate(): LocalDate = {
    val year = parseYearWithByte('-', 9, head)
    val month = parseMonthWithByte('-', head)
    val day = parseDayWithByte(year, month, '"', head)
    LocalDate.of(year, month, day)
  }

  private[this] def parseLocalDateTime(): LocalDateTime = {
    val year = parseYearWithByte('-', 9, head)
    val month = parseMonthWithByte('-', head)
    val day = parseDayWithByte(year, month, 'T', head)
    val hour = parseHourWithColon(head)
    val minute = parseMinute(head)
    val b = nextByte(head)
    var second, nano = 0
    if (b == ':') {
      second = parseSecond(head)
      nano = parseOptionalNanoWithDoubleQuotes()
    } else if (b != '"') tokensError(':', '"')
    LocalDateTime.of(year, month, day, hour, minute, second, nano)
  }

  private[this] def parseLocalTime(): LocalTime = {
    val hour = parseHourWithColon(head)
    val minute = parseMinute(head)
    val b = nextByte(head)
    var second, nano = 0
    if (b == ':') {
      second = parseSecond(head)
      nano = parseOptionalNanoWithDoubleQuotes()
    } else if (b != '"') tokensError(':', '"')
    LocalTime.of(hour, minute, second, nano)
  }

  @tailrec
  private[this] def parseMonthDay(pos: Int): MonthDay =
    if (pos + 7 < tail) {
      val buf = this.buf
      if (buf(pos) != '-') tokenError('-', pos)
      if (buf(pos + 1) != '-') tokenError('-', pos + 1)
      val b3 = buf(pos + 2)
      val b4 = buf(pos + 3)
      val month = b3 * 10 + b4 - 528 // 528 == '0' * 11
      if (b3 < '0' || b3 > '9') digitError(pos + 2)
      if (b4 < '0' || b4 > '9') digitError(pos + 3)
      if (month < 1 || month > 12) monthError(pos + 3)
      if (buf(pos + 4) != '-') tokenError('-', pos + 4)
      val b6 = buf(pos + 5)
      val b7 = buf(pos + 6)
      val day = b6 * 10 + b7 - 528 // 528 == '0' * 11
      head = pos + 8
      if (b6 < '0' || b6 > '9') digitError(pos + 5)
      if (b7 < '0' || b7 > '9') digitError(pos + 6)
      if (day == 0 || (day > 28 && day > maxDayForMonth(month))) dayError(pos + 6)
      if (buf(pos + 7) != '"') tokenError('"', pos + 7)
      MonthDay.of(month, day)
    } else parseMonthDay(loadMoreOrError(pos))

  private[this] def parseOffsetDateTime(): OffsetDateTime = {
    val year = parseYearWithByte('-', 9, head)
    val month = parseMonthWithByte('-', head)
    val day = parseDayWithByte(year, month, 'T', head)
    val hour = parseHourWithColon(head)
    val minute = parseMinute(head)
    var b = nextByte(head)
    var second, nano = 0
    var nanoDigitWeight = -1
    if (b == ':') {
      nanoDigitWeight = -2
      second = parseSecond(head)
      b = nextByte(head)
      if (b == '.') {
        nanoDigitWeight = 100000000
        var pos = head
        var buf = this.buf
        while ({
          if (pos >= tail) {
            pos = loadMoreOrError(pos)
            buf = this.buf
          }
          b = buf(pos)
          pos += 1
          (b >= '0' && b <= '9') && nanoDigitWeight != 0
        }) {
          nano += (b - '0') * nanoDigitWeight
          nanoDigitWeight /= 10
        }
        head = pos
      }
    }
    val zoneOffset =
      if (b == 'Z') {
        nextByteOrError('"', head)
        ZoneOffset.UTC
      } else if (b == '-' || b == '+') toZoneOffset(b, parseOffsetTotalWithDoubleQuotes(head))
      else timeError(nanoDigitWeight)
    OffsetDateTime.of(year, month, day, hour, minute, second, nano, zoneOffset)
  }

  private[this] def parseOffsetTime(): OffsetTime = {
    val hour = parseHourWithColon(head)
    val minute = parseMinute(head)
    var b = nextByte(head)
    var second, nano = 0
    var nanoDigitWeight = -1
    if (b == ':') {
      nanoDigitWeight = -2
      second = parseSecond(head)
      b = nextByte(head)
      if (b == '.') {
        nanoDigitWeight = 100000000
        var pos = head
        var buf = this.buf
        while ({
          if (pos >= tail) {
            pos = loadMoreOrError(pos)
            buf = this.buf
          }
          b = buf(pos)
          pos += 1
          (b >= '0' && b <= '9') && nanoDigitWeight != 0
        }) {
          nano += (b - '0') * nanoDigitWeight
          nanoDigitWeight /= 10
        }
        head = pos
      }
    }
    val zoneOffset =
      if (b == 'Z') {
        nextByteOrError('"', head)
        ZoneOffset.UTC
      } else if (b == '-' || b == '+') toZoneOffset(b, parseOffsetTotalWithDoubleQuotes(head))
      else timeError(nanoDigitWeight)
    OffsetTime.of(hour, minute, second, nano, zoneOffset)
  }

  private[this] def parsePeriod(): Period = {
    var b = nextByte(head)
    var s = 0
    if (b == '-') {
      b = nextByte(head)
      s = -1
    }
    if (b != 'P') durationOrPeriodStartError(s)
    b = nextByte(head)
    var years, months, days, state = 0
    while ({
      var sx = s
      if (b == '-') {
        b = nextByte(head)
        sx = ~sx
      }
      if (b < '0' || b > '9') durationOrPeriodDigitError(s, sx, state)
      var x = '0' - b
      var pos = head
      var buf = this.buf
      while ((pos < tail || {
        pos = loadMore(pos)
        buf = this.buf
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) {
        if (x < -214748364 || {
          x = x * 10 + ('0' - b)
          x > 0
        }) periodError(pos)
        pos += 1
      }
      x = sx - (x ^ sx)
      if ((sx | x) == -2147483648) periodError(pos)
      if (b == 'Y' && state <= 0) {
        years = x
        state = 1
      } else if (b == 'M' && state <= 1) {
        months = x
        state = 2
      } else if (b == 'W' && state <= 2) {
        if (x < -306783378 || x > 306783378) periodError(pos)
        days = x * 7
        state = 3
      } else if (b == 'D') {
        val d = days
        days += x
        state = 4
        if (((x ^ days) & (d ^ days)) < 0) periodError(pos)
      } else periodError(state, pos)
      b = nextByte(pos + 1)
      b != '"'
    }) {
      if (state == 4) tokenError('"')
    }
    Period.of(years, months, days)
  }

  private[this] def parseYearMonth(): YearMonth =
    YearMonth.of(parseYearWithByte('-', 9, head), parseMonthWithByte('"', head))

  private[this] def parseZonedDateTime(): ZonedDateTime = {
    val year = parseYearWithByte('-', 9, head)
    val month = parseMonthWithByte('-', head)
    val day = parseDayWithByte(year, month, 'T', head)
    val hour = parseHourWithColon(head)
    val minute = parseMinute(head)
    var b = nextByte(head)
    var second, nano = 0
    var nanoDigitWeight = -1
    if (b == ':') {
      nanoDigitWeight = -2
      second = parseSecond(head)
      b = nextByte(head)
      if (b == '.') {
        nanoDigitWeight = 100000000
        var pos = head
        var buf = this.buf
        while ({
          if (pos >= tail) {
            pos = loadMoreOrError(pos)
            buf = this.buf
          }
          b = buf(pos)
          pos += 1
          (b >= '0' && b <= '9') && nanoDigitWeight != 0
        }) {
          nano += (b - '0') * nanoDigitWeight
          nanoDigitWeight /= 10
        }
        head = pos
      }
    }
    val localDateTime = LocalDateTime.of(year, month, day, hour, minute, second, nano)
    val zoneOffset =
      if (b == 'Z') {
        b = nextByte(head)
        ZoneOffset.UTC
      } else if (b == '-' || b == '+') {
        val sb = b
        nanoDigitWeight = -3
        var offsetTotal = parseOffsetHour(head) * 3600
        b = nextByte(head)
        if (b == ':') {
          offsetTotal += parseOffsetMinute(head) * 60
          b = nextByte(head)
          if (b == ':') {
            nanoDigitWeight = -4
            offsetTotal += parseOffsetSecond(head)
            b = nextByte(head)
          }
        }
        if (offsetTotal > 64800) timezoneOffsetError() // 64800 == 18 * 60 * 60
        toZoneOffset(sb, offsetTotal)
      } else timeError(nanoDigitWeight)
    if (b == '"') ZonedDateTime.ofLocal(localDateTime, zoneOffset, null)
    else if (b == '[') {
      var pos = head
      var buf = this.buf
      var from = pos
      val oldMark = mark
      val newMark =
        if (oldMark < 0) from
        else oldMark
      mark = newMark
      var hash = 0
      while ({
        if (pos >= tail) {
          pos = loadMoreOrError(pos)
          buf = this.buf
        }
        b = buf(pos)
        pos += 1
        b != ']'
      }) hash = (hash << 5) - hash + b
      if (mark == 0) from -= newMark
      if (mark > oldMark) mark = oldMark
      var k = zoneIdKey
      if (k eq null) {
        k = new Key
        zoneIdKey = k
      }
      k.set(hash, buf, from, pos - 1)
      var zoneId = zoneIds.get(k)
      if (zoneId eq null) zoneId = toZoneId(k, pos)
      if (pos >= tail) {
        pos = loadMoreOrError(pos)
        buf = this.buf
      }
      if (buf(pos) != '"') tokenError('"', pos)
      head = pos + 1
      ZonedDateTime.ofInstant(localDateTime, zoneOffset, zoneId)
    } else zonedDateTimeError(nanoDigitWeight)
  }

  private[this] def toZoneId(k: Key, pos: Int): ZoneId =
    try {
      val zoneId = ZoneId.of(k.toString)
      if (!zoneId.isInstanceOf[ZoneOffset] ||
        (zoneId.asInstanceOf[ZoneOffset].getTotalSeconds * 37283 & 0x1FF8000) == 0) {
        zoneIds.put(k.copy, zoneId)
      }
      return zoneId
    } catch {
      case ex: DateTimeException => timezoneError(ex, pos)
    }

  private[this] def parseZoneOffset(): ZoneOffset = {
    val b = nextByte(head)
    if (b == 'Z') {
      nextByteOrError('"', head)
      ZoneOffset.UTC
    } else if (b == '-' || b == '+') toZoneOffset(b, parseOffsetTotalWithDoubleQuotes(head))
    else decodeError("expected '+' or '-' or 'Z'")
  }

  private[this] def parseOffsetTotalWithDoubleQuotes(pos: Int): Int = {
    var offsetTotal = parseOffsetHour(pos) * 3600
    var b = nextByte(head)
    if (b == ':' && {
      offsetTotal += parseOffsetMinute(head) * 60
      b = nextByte(head)
      b == ':'
    }) offsetTotal += parseOffsetSecondWithDoubleQuotes(head)
    else if (b != '"') tokensError(':', '"')
    if (offsetTotal > 64800) timezoneOffsetError() // 64800 == 18 * 60 * 60
    offsetTotal
  }

  private[this] def toZoneOffset(sb: Byte, offsetTotal: Int): ZoneOffset = {
    var qp = offsetTotal * 37283
    if ((qp & 0x1FF8000) == 0) { // check if offsetTotal divisible by 900
      qp >>>= 25 // divide offsetTotal by 900
      if (sb == '-') qp = -qp
      var zoneOffset = zoneOffsets(qp + 72)
      if (zoneOffset eq null) {
        zoneOffset = ZoneOffset.ofTotalSeconds(if (sb == '-') -offsetTotal else offsetTotal)
        zoneOffsets(qp + 72) = zoneOffset
      }
      zoneOffset
    } else ZoneOffset.ofTotalSeconds(if (sb == '-') -offsetTotal else offsetTotal)
  }

  @inline
  private[this] def epochDay(year: Int, month: Int, day: Int): Long =
    year * 365L + ((year + 3 >> 2) - {
      if (year < 0) year / 100 - year / 400
      else (year + 99) / 100 - (year + 399) / 400
    } + (month * 1002277 - 988622 >> 15) + // (month * 367 - 362) / 12
      (if (month <= 2) -719529
      else if (isLeap(year)) -719530
      else -719531) + day) // 719528 == days 0000 to 1970

  private[this] def maxDayForYearMonth(year: Int, month: Int): Int =
    if (month != 2) month >> 3 ^ (month | 0x1E)
    else if (isLeap(year)) 29
    else 28

  private[this] def maxDayForMonth(month: Int): Int =
    if (month != 2) month >> 3 ^ (month | 0x1E)
    else 29

  private[this] def isLeap(year: Int): Boolean =
    (year & 0x3) == 0 && (year * -1030792151 - 2061584303 > -1975684958 || (year & 0xF) == 0) // year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

  private[this] def digitError(pos: Int): Nothing = decodeError("expected digit", pos)

  private[this] def periodError(pos: Int): Nothing = decodeError("illegal period", pos)

  private[this] def periodError(state: Int, pos: Int): Nothing = decodeError(state match {
    case 0 => "expected 'Y' or 'M' or 'W' or 'D' or digit"
    case 1 => "expected 'M' or 'W' or 'D' or digit"
    case 2 => "expected 'W' or 'D' or digit"
    case _ => "expected 'D' or digit"
  }, pos)

  private[this] def durationOrPeriodStartError(s: Int): Nothing = decodeError {
    if (s < 0) "expected 'P'"
    else "expected 'P' or '-'"
  }

  private[this] def durationOrPeriodDigitError(s: Int, sx: Int, state: Int): Nothing = decodeError {
    if ((s ^ sx) < 0) "expected digit"
    else if (state <= 0) "expected '-' or digit"
    else "expected '\"' or '-' or digit"
  }

  private[this] def durationError(pos: Int): Nothing = decodeError("illegal duration", pos)

  private[this] def durationError(state: Int, pos: Int): Nothing = decodeError(state match {
    case -1 => "expected 'D' or digit"
    case 0 => "expected 'H' or 'M' or 'S' or '.' or digit"
    case 1 => "expected 'M' or 'S' or '.' or digit"
    case _ => "expected 'S' or '.' or digit"
  }, pos)

  private[this] def yearError(t: Byte, maxDigits: Int, pos: Int, b: Byte, yearDigits: Int): Nothing = {
    if (b != '-' && yearDigits == 4) digitError(pos)
    if (yearDigits == maxDigits) tokenError(t, pos)
    tokenOrDigitError(t, pos)
  }

  private[this] def yearError(pos: Int): Nothing = decodeError("illegal year", pos)

  private[this] def monthError(pos: Int): Nothing = decodeError("illegal month", pos)

  private[this] def dayError(pos: Int): Nothing = decodeError("illegal day", pos)

  private[this] def hourError(pos: Int): Nothing = decodeError("illegal hour", pos)

  private[this] def minuteError(pos: Int): Nothing = decodeError("illegal minute", pos)

  private[this] def secondError(pos: Int): Nothing = decodeError("illegal second", pos)

  private[this] def nanoError(nanoDigitWeight: Int, t: Byte, pos: Int): Nothing =
    if (nanoDigitWeight == 0) tokenError(t, pos)
    else tokenOrDigitError(t, pos)

  private[this] def timeError(nanoDigitWeight: Int): Nothing = decodeError {
    if (nanoDigitWeight == -2) "expected '.' or '+' or '-' or 'Z'"
    else if (nanoDigitWeight == -1) "expected ':' or '+' or '-' or 'Z'"
    else if (nanoDigitWeight == 0) "expected '+' or '-' or 'Z'"
    else "expected '+' or '-' or 'Z' or digit"
  }

  private[this] def timezoneError(ex: DateTimeException, pos: Int): Nothing = decodeError("illegal timezone", pos - 1, ex)

  private[this] def timezoneOffsetError(): Nothing = decodeError("illegal timezone offset")

  private[this] def timezoneOffsetHourError(pos: Int): Nothing = decodeError("illegal timezone offset hour", pos)

  private[this] def timezoneOffsetMinuteError(pos: Int): Nothing = decodeError("illegal timezone offset minute", pos)

  private[this] def timezoneOffsetSecondError(pos: Int): Nothing = decodeError("illegal timezone offset second", pos)

  private[this] def zonedDateTimeError(nanoDigitWeight: Int): Nothing = decodeError {
    if (nanoDigitWeight > -3) "expected '[' or '\"'"
    else "expected ':' or '[' or '\"'"
  }

  @tailrec
  private[this] def parseUUID(pos: Int): UUID =
    if (pos + 36 < tail) {
      val ns = nibbles
      val buf = this.buf
      val mostSigBits1 =
        ns(buf(pos) & 0xFF).toLong << 28 |
          (ns(buf(pos + 1) & 0xFF) << 24 |
            ns(buf(pos + 2) & 0xFF) << 20 |
            ns(buf(pos + 3) & 0xFF) << 16 |
            ns(buf(pos + 4) & 0xFF) << 12 |
            ns(buf(pos + 5) & 0xFF) << 8 |
            ns(buf(pos + 6) & 0xFF) << 4 |
            ns(buf(pos + 7) & 0xFF))
      if (mostSigBits1 < 0L) hexDigitError(pos)
      if (buf(pos + 8) != '-') tokenError('-', pos + 8)
      val mostSigBits2 =
        ns(buf(pos + 9) & 0xFF) << 12 |
          ns(buf(pos + 10) & 0xFF) << 8 |
          ns(buf(pos + 11) & 0xFF) << 4 |
          ns(buf(pos + 12) & 0xFF)
      if (mostSigBits2 < 0) hexDigitError(pos + 9)
      if (buf(pos + 13) != '-') tokenError('-', pos + 13)
      val mostSigBits3 =
        ns(buf(pos + 14) & 0xFF) << 12 |
          ns(buf(pos + 15) & 0xFF) << 8 |
          ns(buf(pos + 16) & 0xFF) << 4 |
          ns(buf(pos + 17) & 0xFF)
      if (mostSigBits3 < 0) hexDigitError(pos + 14)
      if (buf(pos + 18) != '-') tokenError('-', pos + 18)
      val leastSigBits1 =
        ns(buf(pos + 19) & 0xFF) << 12 |
          ns(buf(pos + 20) & 0xFF) << 8 |
          ns(buf(pos + 21) & 0xFF) << 4 |
          ns(buf(pos + 22) & 0xFF)
      if (leastSigBits1 < 0) hexDigitError(pos + 19)
      if (buf(pos + 23) != '-') tokenError('-', pos + 23)
      val leastSigBits2 =
        (ns(buf(pos + 24) & 0xFF) << 16 |
          ns(buf(pos + 25) & 0xFF) << 12 |
          ns(buf(pos + 26) & 0xFF) << 8 |
          ns(buf(pos + 27) & 0xFF) << 4 |
          ns(buf(pos + 28) & 0xFF)).toLong << 28 |
          (ns(buf(pos + 29) & 0xFF) << 24 |
            ns(buf(pos + 30) & 0xFF) << 20 |
            ns(buf(pos + 31) & 0xFF) << 16 |
            ns(buf(pos + 32) & 0xFF) << 12 |
            ns(buf(pos + 33) & 0xFF) << 8 |
            ns(buf(pos + 34) & 0xFF) << 4 |
            ns(buf(pos + 35) & 0xFF))
      if (leastSigBits2 < 0L) hexDigitError(pos + 24)
      if (buf(pos + 36) != '"') tokenError('"', pos + 36)
      head = pos + 37
      new UUID(mostSigBits1 << 32 | mostSigBits2.toLong << 16 | mostSigBits3,
        leastSigBits1.toLong << 48 | leastSigBits2)
    } else parseUUID(loadMoreOrError(pos))

  @tailrec
  private[this] def parseString(i: Int, minLim: Int, charBuf: Array[Char], pos: Int): Int =
    if (i < minLim) {
      val b = buf(pos)
      charBuf(i) = b.toChar
      if (b == '"') {
        head = pos + 1
        i
      } else if ((b - 0x20 ^ 0x3C) <= 0) parseEncodedString(i, charBuf.length - 1, charBuf, pos)
      else parseString(i + 1, minLim, charBuf, pos + 1)
    } else if (pos >= tail) {
      val newPos = loadMoreOrError(pos)
      parseString(i, Math.min(charBuf.length, i + tail - newPos), charBuf, newPos)
    } else parseString(i, Math.min(growCharBuf(i + 1), i + tail - pos), this.charBuf, pos)

  @tailrec
  private[this] def parseEncodedString(i: Int, lim: Int, charBuf: Array[Char], pos: Int): Int = {
    val remaining = tail - pos
    if (i < lim) {
      if (remaining > 0) {
        val b1 = buf(pos)
        if (b1 >= 0) {
          if (b1 == '"') {
            head = pos + 1
            i
          } else if (b1 != '\\') { // 0aaaaaaa (UTF-8 byte) -> 000000000aaaaaaa (UTF-16 char)
            if (b1 < ' ') unescapedControlCharacterError(pos)
            charBuf(i) = b1.toChar
            parseEncodedString(i + 1, lim, charBuf, pos + 1)
          } else if (remaining > 1) {
            val b2 = buf(pos + 1)
            if (b2 != 'u') {
              charBuf(i) = (b2: @switch) match {
                case '"' => '"'
                case 'n' => '\n'
                case 'r' => '\r'
                case 't' => '\t'
                case 'b' => '\b'
                case 'f' => '\f'
                case '\\' => '\\'
                case '/' => '/'
                case _ => escapeSequenceError(pos + 1)
              }
              parseEncodedString(i + 1, lim, charBuf, pos + 2)
            } else if (remaining > 5) {
              val ch1 = readEscapedUnicode(pos + 2, buf)
              charBuf(i) = ch1
              if ((ch1 & 0xF800) != 0xD800) parseEncodedString(i + 1, lim, charBuf, pos + 6)
              else if (remaining > 11) {
                if (buf(pos + 6) != '\\') escapeSequenceError(pos + 6)
                if (buf(pos + 7) != 'u') escapeSequenceError(pos + 7)
                val ch2 = readEscapedUnicode(pos + 8, buf)
                charBuf(i + 1) = ch2
                if (ch1 >= 0xDC00 || (ch2 & 0xFC00) != 0xDC00) decodeError("illegal surrogate character pair", pos + 11)
                parseEncodedString(i + 2, lim, charBuf, pos + 12)
              } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
            } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else if ((b1 & 0xE0) == 0xC0) { // 110bbbbb 10aaaaaa (UTF-8 bytes) -> 00000bbbbbaaaaaa (UTF-16 char)
          if (remaining > 1) {
            val b2 = buf(pos + 1)
            val ch = (b1 << 6 ^ b2 ^ 0xF80).toChar // 0xF80 == 0xC0.toByte << 6 ^ 0x80.toByte
            charBuf(i) = ch
            if ((b2 & 0xC0) != 0x80 || ch < 0x80) malformedBytesError(b1, b2, pos)
            parseEncodedString(i + 1, lim, charBuf, pos + 2)
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else if ((b1 & 0xF0) == 0xE0) { // 1110cccc 10bbbbbb 10aaaaaa (UTF-8 bytes) -> ccccbbbbbbaaaaaa (UTF-16 char)
          if (remaining > 2) {
            val b2 = buf(pos + 1)
            val b3 = buf(pos + 2)
            val ch = (b1 << 12 ^ b2 << 6 ^ b3 ^ 0x1F80).toChar // 0x1F80 == (0x80.toByte << 6 ^ 0x80.toByte).toChar
            charBuf(i) = ch
            if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80 || ch < 0x800 ||
              (ch & 0xF800) == 0xD800) malformedBytesError(b1, b2, b3, pos)
            parseEncodedString(i + 1, lim, charBuf, pos + 3)
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else if ((b1 & 0xF8) == 0xF0) { // 11110ddd 10ddcccc 10bbbbbb 10aaaaaa (UTF-8 bytes) -> 110110uuuuccccbb 110111bbbbaaaaaa (UTF-16 chars), where uuuu = ddddd - 1
          if (remaining > 3) {
            val b2 = buf(pos + 1)
            val b3 = buf(pos + 2)
            val b4 = buf(pos + 3)
            val cp = b1 << 18 ^ b2 << 12 ^ b3 << 6 ^ b4 ^ 0x381F80 // 0x381F80 == 0xF0.toByte << 18 ^ 0x80.toByte << 12 ^ 0x80.toByte << 6 ^ 0x80.toByte
            val ch1 = ((cp >>> 10) + 0xD7C0).toChar // 0xD7C0 == 0xD800 - (0x10000 >>> 10)
            charBuf(i) = ch1
            charBuf(i + 1) = ((cp & 0x3FF) | 0xDC00).toChar
            if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80 || (b4 & 0xC0) != 0x80 ||
              (ch1 & 0xF800) != 0xD800) malformedBytesError(b1, b2, b3, b4, pos)
            parseEncodedString(i + 2, lim, charBuf, pos + 4)
          } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
        } else malformedBytesError(b1, pos)
      } else parseEncodedString(i, lim, charBuf, loadMoreOrError(pos))
    } else parseEncodedString(i, growCharBuf(i + 2) - 1, this.charBuf, pos) // 2 is length of surrogate pair
  }

  @tailrec
  private[this] def parseChar(pos: Int): Char = {
    val remaining = tail - pos
    if (remaining > 0) {
      val b1 = buf(pos)
      if (b1 >= 0) {
        if (b1 == '"') characterError(pos)
        else if (b1 != '\\') { // 0aaaaaaa (UTF-8 byte) -> 000000000aaaaaaa (UTF-16 char)
          if (b1 < ' ') unescapedControlCharacterError(pos)
          head = pos + 1
          return b1.toChar
        } else if (remaining > 1) {
          val b2 = buf(pos + 1)
          if (b2 != 'u') {
            head = pos + 2
            (b2: @switch) match {
              case 'b' => return '\b'
              case 'f' => return '\f'
              case 'n' => return '\n'
              case 'r' => return '\r'
              case 't' => return '\t'
              case '"' => return '"'
              case '/' => return '/'
              case '\\' => return '\\'
              case _ => escapeSequenceError(pos + 1)
            }
          } else if (remaining > 5) {
            val ch = readEscapedUnicode(pos + 2, buf)
            head = pos + 6
            if ((ch & 0xF800) == 0xD800) surrogateCharacterError(pos + 5)
            return ch
          }
        }
      } else if ((b1 & 0xE0) == 0xC0) { // 110bbbbb 10aaaaaa (UTF-8 bytes) -> 00000bbbbbaaaaaa (UTF-16 char)
        if (remaining > 1) {
          val b2 = buf(pos + 1)
          val ch = (b1 << 6 ^ b2 ^ 0xF80).toChar // 0xF80 == 0xC0.toByte << 6 ^ 0x80.toByte
          head = pos + 2
          if ((b2 & 0xC0) != 0x80 || ch < 0x80) malformedBytesError(b1, b2, pos)
          return ch
        }
      } else if ((b1 & 0xF0) == 0xE0) { // 1110cccc 10bbbbbb 10aaaaaa (UTF-8 bytes) -> ccccbbbbbbaaaaaa (UTF-16 char)
        if (remaining > 2) {
          val b2 = buf(pos + 1)
          val b3 = buf(pos + 2)
          val ch = (b1 << 12 ^ b2 << 6 ^ b3 ^ 0x1F80).toChar // 0x1F80 == (0x80.toByte << 6 ^ 0x80.toByte).toChar
          head = pos + 3
          if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80 || ch < 0x800 ||
            (ch & 0xF800) == 0xD800) malformedBytesError(b1, b2, b3, pos)
          return ch
        }
      } else if ((b1 & 0xF8) == 0xF0) surrogateCharacterError(pos + 3)
      else malformedBytesError(b1, pos)
    }
    parseChar(loadMoreOrError(pos))
  }

  @inline
  private[this] def readEscapedUnicode(pos: Int, buf: Array[Byte]): Char = {
    val ns = nibbles
    val x =
      ns(buf(pos) & 0xFF) << 12 |
        ns(buf(pos + 1) & 0xFF) << 8 |
        ns(buf(pos + 2) & 0xFF) << 4 |
        ns(buf(pos + 3) & 0xFF)
    if (x < 0) hexDigitError(pos)
    x.toChar
  }

  private[this] def parseBase16(ns: Array[Byte]): Array[Byte] = {
    var charBuf = this.charBuf
    var len = charBuf.length
    var pos = head
    var buf = this.buf
    var i, bits = 0
    while (bits >= 0 && (pos + 3 < tail || {
      pos = loadMore(pos)
      buf = this.buf
      pos + 3 < tail
    })) {
      if (i >= len) {
        len = growCharBuf(i + 1)
        charBuf = this.charBuf
      }
      val posLim = Math.min(tail - 3, (len - i << 2) + pos)
      while (pos < posLim && {
        bits =
          ns(buf(pos) & 0xFF) << 12 |
            ns(buf(pos + 1) & 0xFF) << 8 |
            ns(buf(pos + 2) & 0xFF) << 4 |
            ns(buf(pos + 3) & 0xFF)
        bits >= 0
      }) {
        charBuf(i) = bits.toChar
        i += 1
        pos += 4
      }
    }
    val bLen = i << 1
    var bs: Array[Byte] = null
    var b = nextByte(pos)
    if (b == '"') bs = new Array[Byte](bLen)
    else {
      bits = ns(b & 0xFF).toInt
      if (bits < 0) decodeError("expected '\"' or hex digit")
      b = nextByte(head)
      bits = bits << 4 | ns(b & 0xFF)
      if (bits < 0) decodeError("expected hex digit")
      b = nextByte(head)
      if (b != '"') {
        if (ns(b & 0xFF) < 0) decodeError("expected '\"' or hex digit")
        b = nextByte(head)
        decodeError("expected hex digit")
      }
      bs = new Array[Byte](bLen + 1)
      bs(bLen) = bits.toByte
    }
    i = 0
    var j = 0
    while (j < bLen) {
      val ch = charBuf(i)
      bs(j) = (ch >> 8).toByte
      bs(j + 1) = ch.toByte
      i += 1
      j += 2
    }
    bs
  }

  private[this] def parseBase64(ds: Array[Byte]): Array[Byte] = {
    var charBuf = this.charBuf
    var lenM1 = charBuf.length - 1
    var pos = head
    var buf = this.buf
    var i, bits = 0
    while (bits >= 0 && (pos + 3 < tail || {
      pos = loadMore(pos)
      buf = this.buf
      pos + 3 < tail
    })) {
      if (i >= lenM1) {
        lenM1 = growCharBuf(i + 1) - 1
        charBuf = this.charBuf
      }
      val posLim = Math.min(tail - 3, (lenM1 - i << 1) + pos)
      while (pos < posLim && {
        bits =
          ds(buf(pos) & 0xFF) << 18 |
            ds(buf(pos + 1) & 0xFF) << 12 |
            ds(buf(pos + 2) & 0xFF) << 6 |
            ds(buf(pos + 3) & 0xFF)
        bits >= 0
      }) {
        charBuf(i) = (bits >> 8).toChar
        charBuf(i + 1) = bits.toChar
        i += 2
        pos += 4
      }
    }
    val bLen = i + (i >> 1)
    var bs: Array[Byte] = null
    var b = nextByte(pos)
    if (b == '"') bs = new Array[Byte](bLen)
    else {
      bits = ds(b & 0xFF).toInt
      if (bits < 0) decodeError("expected '\"' or base64 digit")
      b = nextByte(head)
      bits = bits << 6 | ds(b & 0xFF)
      if (bits < 0) decodeError("expected base64 digit")
      b = nextByte(head)
      if (b == '"' || b == '=') {
        if (b == '=') {
          nextByteOrError('=', head)
          nextByteOrError('"', head)
        }
        bs = new Array[Byte](bLen + 1)
        bs(bLen) = (bits >> 4).toByte
      } else {
        bits = bits << 6 | ds(b & 0xFF)
        if (bits < 0) decodeError("expected '\"' or '=' or base64 digit")
        b = nextByte(head)
        if (b == '=') nextByteOrError('"', head)
        else if (b != '"') tokensError('"', '=')
        bs = new Array[Byte](bLen + 2)
        bs(bLen) = (bits >> 10).toByte
        bs(bLen + 1) = (bits >> 2).toByte
      }
    }
    i = 0
    var j = 0
    while (j < bLen) {
      val ch = charBuf(i)
      bs(j) = (ch >> 8).toByte
      bs(j + 1) = ch.toByte
      bs(j + 2) = charBuf(i + 1).toByte
      i += 2
      j += 3
    }
    bs
  }

  @tailrec
  private[this] def hexDigitError(pos: Int): Nothing = {
    if (nibbles(buf(pos) & 0xFF) < 0) decodeError("expected hex digit", pos)
    hexDigitError(pos + 1)
  }

  private[this] def characterError(pos: Int): Nothing = decodeError("illegal character", pos)

  private[this] def escapeSequenceError(pos: Int): Nothing = decodeError("illegal escape sequence", pos)

  private[this] def surrogateCharacterError(pos: Int): Nothing = decodeError("illegal surrogate character", pos)

  private[this] def unescapedControlCharacterError(pos: Int): Nothing = decodeError("unescaped control character", pos)

  private[this] def malformedBytesError(b1: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHexByte(b1, i, hexDigits)
    decodeError(i, pos, null)
  }

  private[this] def malformedBytesError(b1: Byte, b2: Byte, pos: Int): Nothing = {
    val ds = hexDigits
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHexByte(b1, i, ds)
    i = appendString(", 0x", i)
    i = appendHexByte(b2, i, ds)
    decodeError(i, pos + 1, null)
  }

  private[this] def malformedBytesError(b1: Byte, b2: Byte, b3: Byte, pos: Int): Nothing = {
    val ds = hexDigits
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHexByte(b1, i, ds)
    i = appendString(", 0x", i)
    i = appendHexByte(b2, i, ds)
    i = appendString(", 0x", i)
    i = appendHexByte(b3, i, ds)
    decodeError(i, pos + 2, null)
  }

  private[this] def malformedBytesError(b1: Byte, b2: Byte, b3: Byte, b4: Byte, pos: Int): Nothing = {
    val ds = hexDigits
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHexByte(b1, i, ds)
    i = appendString(", 0x", i)
    i = appendHexByte(b2, i, ds)
    i = appendString(", 0x", i)
    i = appendHexByte(b3, i, ds)
    i = appendString(", 0x", i)
    i = appendHexByte(b4, i, ds)
    decodeError(i, pos + 3, null)
  }

  private[this] def appendHexDump(pos: Int, offset: Int, from: Int): Int = {
    val hexDumpSizeInBytes = config.hexDumpSize << 4
    val start = Math.max(pos - hexDumpSizeInBytes & 0xFFFFFFF0, 0)
    val end = Math.min(pos + hexDumpSizeInBytes + 16 & 0xFFFFFFF0, tail)
    val alignedAbsFrom = start + offset & 0xFFFFFFF0
    val alignedAbsTo = end + offset + 15 & 0xFFFFFFF0
    val len = alignedAbsTo - alignedAbsFrom
    val bufOffset = alignedAbsFrom - offset
    var i = appendChars(dumpBorder, from)
    i = appendChars(dumpHeader, i)
    i = appendChars(dumpBorder, i)
    val buf = this.buf
    val ds = hexDigits
    var charBuf = this.charBuf
    var lim = charBuf.length
    var j = 0
    while (j < len) {
      val linePos = j & 0xF
      if (linePos == 0) {
        if (i + 81 >= lim) { // 81 == dumpBorder.length
          lim = growCharBuf(i + 81)
          charBuf = this.charBuf
        }
        charBuf(i) = '\n'
        charBuf(i + 1) = '|'
        charBuf(i + 2) = ' '
        putHexInt(alignedAbsFrom + j, i + 3, charBuf, ds)
        charBuf(i + 11) = ' '
        charBuf(i + 12) = '|'
        charBuf(i + 13) = ' '
        i += 14
      }
      val pos = bufOffset + j
      charBuf(i + 50 - (linePos << 1)) =
        if (pos >= start && pos < end) {
          val b = buf(pos)
          charBuf(i) = ds(b >> 4 & 0xF)
          charBuf(i + 1) = ds(b & 0xF)
          charBuf(i + 2) = ' '
          if (b <= 31 || b >= 127) '.'
          else b.toChar
        } else {
          charBuf(i) = ' '
          charBuf(i + 1) = ' '
          charBuf(i + 2) = ' '
          ' '
        }
      i += 3
      if (linePos == 15) {
        charBuf(i) = '|'
        charBuf(i + 1) = ' '
        charBuf(i + 18) = ' '
        charBuf(i + 19) = '|'
        i += 20
      }
      j += 1
    }
    appendChars(dumpBorder, i)
  }

  private[this] def appendHexOffset(d: Long, i: Int): Int = {
    ensureCharBufCapacity(i + 16)
    val ds = hexDigits
    var j = i
    val dl = d.toInt
    if (dl != d) {
      val dh = (d >>> 32).toInt
      var shift = 32 - java.lang.Integer.numberOfLeadingZeros(dh) & 0x1C
      while (shift >= 0) {
        charBuf(j) = ds(dh >> shift & 0xF)
        shift -= 4
        j += 1
      }
    }
    putHexInt(dl, j, charBuf, ds)
    j + 8
  }

  private[this] def appendHexByte(b: Byte, i: Int, ds: Array[Char]): Int = {
    ensureCharBufCapacity(i + 2)
    charBuf(i) = ds(b >> 4 & 0xF)
    charBuf(i + 1) = ds(b & 0xF)
    i + 2
  }

  @inline
  private[this] def putHexInt(d: Int, i: Int, charBuf: Array[Char], ds: Array[Char]): Unit = {
    charBuf(i) = ds(d >>> 28)
    charBuf(i + 1) = ds(d >> 24 & 0xF)
    charBuf(i + 2) = ds(d >> 20 & 0xF)
    charBuf(i + 3) = ds(d >> 16 & 0xF)
    charBuf(i + 4) = ds(d >> 12 & 0xF)
    charBuf(i + 5) = ds(d >> 8 & 0xF)
    charBuf(i + 6) = ds(d >> 4 & 0xF)
    charBuf(i + 7) = ds(d & 0xF)
  }

  private[this] def growCharBuf(required: Int): Int = {
    var charBufLen = charBuf.length
    val maxCharBufSize = config.maxCharBufSize
    if (charBufLen == maxCharBufSize) tooLongStringError()
    charBufLen = (-1 >>> Integer.numberOfLeadingZeros(charBufLen | required)) + 1
    if (Integer.compareUnsigned(charBufLen, maxCharBufSize) > 0) charBufLen = maxCharBufSize
    charBuf = java.util.Arrays.copyOf(charBuf, charBufLen)
    charBufLen
  }

  @inline
  private[this] def ensureCharBufCapacity(required: Int): Unit =
    if (charBuf.length < required) growCharBuf(required): Unit

  @tailrec
  private[this] def skipString(evenBackSlashes: Boolean, pos: Int): Int =
    if (pos < tail) {
      if (evenBackSlashes) {
        val b = buf(pos)
        if (b == '"') pos + 1
        else skipString(b != '\\', pos + 1)
      } else skipString(evenBackSlashes = true, pos + 1)
    } else skipString(evenBackSlashes, loadMoreOrError(pos))

  private[this] def skipNumber(p: Int): Int = {
    var pos = p
    var buf = this.buf
    while ((pos < tail || {
      pos = loadMore(pos)
      buf = this.buf
      pos < tail
    }) && {
      val b = buf(pos)
      (b >= '0' && b <= '9') || b == '.' || (b | 0x20) == 'e' || b == '-' || b == '+'
    }) pos += 1
    pos
  }

  @tailrec
  private[this] def skipObject(level: Int, pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if (b == '"') skipObject(level, skipString(evenBackSlashes = true, pos + 1))
      else if (b == '{') skipObject(level + 1, pos + 1)
      else if (b != '}') skipObject(level, pos + 1)
      else if (level != 0) skipObject(level - 1, pos + 1)
      else pos + 1
    } else skipObject(level, loadMoreOrError(pos))

  @tailrec
  private[this] def skipArray(level: Int, pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if (b == '"') skipArray(level, skipString(evenBackSlashes = true, pos + 1))
      else if (b == '[') skipArray(level + 1, pos + 1)
      else if (b != ']') skipArray(level, pos + 1)
      else if (level != 0) skipArray(level - 1, pos + 1)
      else pos + 1
    } else skipArray(level, loadMoreOrError(pos))

  @tailrec
  private[this] def skipFixedBytes(n: Int, pos: Int): Int = {
    val newPos = pos + n
    if (newPos <= tail) newPos
    else skipFixedBytes(n, loadMoreOrError(pos))
  }

  private[this] def loadMoreOrError(pos: Int): Int = {
    if ((bbuf eq null) && (in eq null)) endOfInputError()
    loadMore(pos, throwOnEndOfInput = true)
  }

  private[this] def loadMore(pos: Int): Int =
    if ((bbuf eq null) && (in eq null)) pos
    else loadMore(pos, throwOnEndOfInput = false)

  private[this] def loadMore(pos: Int, throwOnEndOfInput: Boolean): Int = {
    var newPos = pos
    val offset =
      if (mark < 0) pos
      else mark
    if (offset > 0) {
      newPos -= offset
      val buf = this.buf
      val remaining = tail - offset
      var i = 0
      while (i < remaining) {
        buf(i) = buf(i + offset)
        i += 1
      }
      if (mark > 0) mark = 0
      tail = remaining
      head = newPos
    } else growBuf()
    var len = buf.length - tail
    if (bbuf ne null) {
      len = Math.min(bbuf.remaining, len)
      bbuf.get(buf, tail, len)
    } else len = Math.max(in.read(buf, tail, len), 0)
    if (throwOnEndOfInput && len == 0) endOfInputError()
    tail += len
    totalRead += len
    newPos
  }

  private[this] def growBuf(): Unit = {
    var bufLen = buf.length
    val maxBufSize = config.maxBufSize
    if (bufLen == maxBufSize) tooLongInputError()
    bufLen <<= 1
    if (Integer.compareUnsigned(bufLen, maxBufSize) > 0) bufLen = maxBufSize
    buf = java.util.Arrays.copyOf(buf, bufLen)
  }

  private[this] def tooLongInputError(): Nothing = decodeError("too long part of input exceeded 'maxBufSize'", tail)

  private[this] def tooLongStringError(): Nothing = decodeError("too long string exceeded 'maxCharBufSize'", tail)

  private[this] def endOfInputError(): Nothing = decodeError("unexpected end of input", tail)

  private[this] def reallocateBufToPreferredSize(): Unit = buf = new Array[Byte](config.preferredBufSize)

  private[this] def reallocateCharBufToPreferredSize(): Unit = charBuf = new Array[Char](config.preferredCharBufSize)
}

object JsonReader {
  private final val pow10Doubles: Array[Double] =
    Array(1, 1e+1, 1e+2, 1e+3, 1e+4, 1e+5, 1e+6, 1e+7, 1e+8, 1e+9, 1e+10, 1e+11,
      1e+12, 1e+13, 1e+14, 1e+15, 1e+16, 1e+17, 1e+18, 1e+19, 1e+20, 1e+21, 1e+22)
  /* Use the following code to generate `pow10Mantissas` in Scala REPL:
    val ms = new Array[Long](653)
    var pow10 = BigInt(10)
    var i = 342
    while (i >= 0) {
      ms(i) = ((BigInt(1) << (pow10.bitLength + 63)) / pow10).longValue
      pow10 *= 10
      i -= 1
    }
    pow10 = BigInt(1) << 63
    i = 343
    while (i < 653) {
      ms(i) = (pow10 >> (pow10.bitLength - 64)).longValue
      pow10 *= 10
      i += 1
    }
    ms.grouped(4).map(_.mkString("L, ")).mkString("Array(\n", "L,\n", "L\n)")
  */
  private final val pow10Mantissas: Array[Long] = Array(
    -4671960508600951122L, -1228264617323800998L, -7685194413468457480L, -4994806998408183946L,
    -1631822729582842029L, -7937418233630358124L, -5310086773610559751L, -2025922448585811785L,
    -8183730558007214222L, -5617977179081629873L, -2410785455424649437L, -8424269937281487754L,
    -5918651403174471789L, -2786628235540701832L, -8659171674854020501L, -6212278575140137722L,
    -3153662200497784248L, -8888567902952197011L, -6499023860262858360L, -3512093806901185046L,
    -9112587656954322510L, -6779048552765515233L, -3862124672529506138L, -215969822234494768L,
    -7052510166537641086L, -4203951689744663454L, -643253593753441413L, -7319562523736982739L,
    -4537767136243840520L, -1060522901877412746L, -7580355841314464822L, -4863758783215693124L,
    -1468012460592228501L, -7835036815511224669L, -5182110000961642932L, -1865951482774665761L,
    -8083748704375247957L, -5492999862041672042L, -2254563809124702148L, -8326631408344020699L,
    -5796603242002637969L, -2634068034075909558L, -8563821548938525330L, -6093090917745768758L,
    -3004677628754823043L, -8795452545612846258L, -6382629663588669919L, -3366601061058449494L,
    -9021654690802612790L, -6665382345075878084L, -3720041912917459700L, -38366372719436721L,
    -6941508010590729807L, -4065198994811024355L, -469812725086392539L, -7211161980820077193L,
    -4402266457597708587L, -891147053569747830L, -7474495936122174250L, -4731433901725329908L,
    -1302606358729274481L, -7731658001846878407L, -5052886483881210105L, -1704422086424124727L,
    -7982792831656159810L, -5366805021142811859L, -2096820258001126919L, -8228041688891786181L,
    -5673366092687344822L, -2480021597431793123L, -8467542526035952558L, -5972742139117552794L,
    -2854241655469553088L, -8701430062309552536L, -6265101559459552766L, -3219690930897053053L,
    -8929835859451740015L, -6550608805887287114L, -3576574988931720989L, -9152888395723407474L,
    -6829424476226871438L, -3925094576856201394L, -294682202642863838L, -7101705404292871755L,
    -4265445736938701790L, -720121152745989333L, -7367604748107325189L, -4597819916706768583L,
    -1135588877456072824L, -7627272076051127371L, -4922404076636521310L, -1541319077368263733L,
    -7880853450996246689L, -5239380795317920458L, -1937539975720012668L, -8128491512466089774L,
    -5548928372155224313L, -2324474446766642487L, -8370325556870233411L, -5851220927660403859L,
    -2702340141148116920L, -8606491615858654931L, -6146428501395930760L, -3071349608317525546L,
    -8837122532839535322L, -6434717147622031249L, -3431710416100151157L, -9062348037703676329L,
    -6716249028702207507L, -3783625267450371480L, -117845565885576446L, -6991182506319567135L,
    -4127292114472071014L, -547429124662700864L, -7259672230555269896L, -4462904269766699466L,
    -966944318780986428L, -7521869226879198374L, -4790650515171610063L, -1376627125537124675L,
    -7777920981101784778L, -5110715207949843068L, -1776707991509915931L, -8027971522334779313L,
    -5423278384491086237L, -2167411962186469893L, -8272161504007625539L, -5728515861582144020L,
    -2548958808550292121L, -8510628282985014432L, -6026599335303880135L, -2921563150702462265L,
    -8743505996830120772L, -6317696477610263061L, -3285434578585440922L, -8970925639256982432L,
    -6601971030643840136L, -3640777769877412266L, -9193015133814464522L, -6879582898840692749L,
    -3987792605123478032L, -373054737976959636L, -7150688238876681629L, -4326674280168464132L,
    -796656831783192261L, -7415439547505577019L, -4657613415954583370L, -1210330751515841308L,
    -7673985747338482674L, -4980796165745715438L, -1614309188754756393L, -7926472270612804602L,
    -5296404319838617848L, -2008819381370884406L, -8173041140997884610L, -5604615407819967859L,
    -2394083241347571919L, -8413831053483314306L, -5905602798426754978L, -2770317479606055818L,
    -8648977452394866743L, -6199535797066195524L, -3137733727905356501L, -8878612607581929669L,
    -6486579741050024183L, -3496538657885142324L, -9102865688819295809L, -6766896092596731857L,
    -3846934097318526917L, -196981603220770742L, -7040642529654063570L, -4189117143640191558L,
    -624710411122851544L, -7307973034592864071L, -4523280274813692185L, -1042414325089727327L,
    -7569037980822161435L, -4849611457600313890L, -1450328303573004458L, -7823984217374209643L,
    -5168294253290374149L, -1848681798185579782L, -8072955151507069220L, -5479507920956448621L,
    -2237698882768172872L, -8316090829371189901L, -5783427518286599473L, -2617598379430861437L,
    -8553528014785370254L, -6080224000054324913L, -2988593981640518238L, -8785400266166405755L,
    -6370064314280619289L, -3350894374423386208L, -9011838011655698236L, -6653111496142234891L,
    -3704703351750405709L, -19193171260619233L, -6929524759678968877L, -4050219931171323192L,
    -451088895536766085L, -7199459587351560659L, -4387638465762062920L, -872862063775190746L,
    -7463067817500576073L, -4717148753448332187L, -1284749923383027329L, -7720497729755473937L,
    -5038936143766954517L, -1686984161281305242L, -7971894128441897632L, -5353181642124984136L,
    -2079791034228842266L, -8217398424034108273L, -5660062011615247437L, -2463391496091671392L,
    -8457148712698376476L, -5959749872445582691L, -2838001322129590460L, -8691279853972075893L,
    -6252413799037706963L, -3203831230369745799L, -8919923546622172981L, -6538218414850328322L,
    -3561087000135522498L, -9143208402725783417L, -6817324484979841368L, -3909969587797413806L,
    -275775966319379353L, -7089889006590693952L, -4250675239810979535L, -701658031336336515L,
    -7356065297226292178L, -4583395603105477319L, -1117558485454458744L, -7616003081050118571L,
    -4908317832885260310L, -1523711272679187483L, -7869848573065574033L, -5225624697904579637L,
    -1920344853953336643L, -8117744561361917258L, -5535494683275008668L, -2307682335666372931L,
    -8359830487432564938L, -5838102090863318269L, -2685941595151759932L, -8596242524610931813L,
    -6133617137336276863L, -3055335403242958174L, -8827113654667930715L, -6422206049907525490L,
    -3416071543957018958L, -9052573742614218705L, -6704031159840385477L, -3768352931373093942L,
    -98755145788979524L, -6979250993759194058L, -4112377723771604669L, -528786136287117932L,
    -7248020362820530564L, -4448339435098275301L, -948738275445456222L, -7510490449794491995L,
    -4776427043815727089L, -1358847786342270957L, -7766808894105001205L, -5096825099203863602L,
    -1759345355577441598L, -8017119874876982855L, -5409713825168840664L, -2150456263033662926L,
    -8261564192037121185L, -5715269221619013577L, -2532400508596379068L, -8500279345513818773L,
    -6013663163464885563L, -2905392935903719049L, -8733399612580906262L, -6305063497298744923L,
    -3269643353196043250L, -8961056123388608887L, -6589634135808373205L, -3625356651333078602L,
    -9183376934724255983L, -6867535149977932074L, -3972732919045027189L, -354230130378896082L,
    -7138922859127891907L, -4311967555482476980L, -778273425925708321L, -7403949918844649557L,
    -4643251380128424042L, -1192378206733142148L, -7662765406849295699L, -4966770740134231719L,
    -1596777406740401745L, -7915514906853832947L, -5282707615139903279L, -1991698500497491195L,
    -8162340590452013853L, -5591239719637629412L, -2377363631119648861L, -8403381297090862394L,
    -5892540602936190089L, -2753989735242849707L, -8638772612167862923L, -6186779746782440750L,
    -3121788665050663033L, -8868646943297746252L, -6474122660694794911L, -3480967307441105734L,
    -9093133594791772940L, -6754730975062328271L, -3831727700400522434L, -177973607073265139L,
    -7028762532061872568L, -4174267146649952806L, -606147914885053103L, -7296371474444240046L,
    -4508778324627912153L, -1024286887357502287L, -7557708332239520786L, -4835449396872013078L,
    -1432625727662628443L, -7812920107430224633L, -5154464115860392887L, -1831394126398103205L,
    -8062150356639896359L, -5466001927372482545L, -2220816390788215277L, -8305539271883716405L,
    -5770238071427257602L, -2601111570856684098L, -8543223759426509417L, -6067343680855748868L,
    -2972493582642298180L, -8775337516792518219L, -6357485877563259869L, -3335171328526686933L,
    -9002011107970261189L, -6640827866535438582L, -3689348814741910324L, -9223372036854775808L,
    -6917529027641081856L, -4035225266123964416L, -432345564227567616L, -7187745005283311616L,
    -4372995238176751616L, -854558029293551616L, -7451627795949551616L, -4702848726509551616L,
    -1266874889709551616L, -7709325833709551616L, -5024971273709551616L, -1669528073709551616L,
    -7960984073709551616L, -5339544073709551616L, -2062744073709551616L, -8206744073709551616L,
    -5646744073709551616L, -2446744073709551616L, -8446744073709551616L, -5946744073709551616L,
    -2821744073709551616L, -8681119073709551616L, -6239712823709551616L, -3187955011209551616L,
    -8910000909647051616L, -6525815118631426616L, -3545582879861895366L, -9133518327554766460L,
    -6805211891016070171L, -3894828845342699810L, -256850038250986858L, -7078060301547948643L,
    -4235889358507547899L, -683175679707046970L, -7344513827457986212L, -4568956265895094861L,
    -1099509313941480672L, -7604722348854507276L, -4894216917640746191L, -1506085128623544835L,
    -7858832233030797378L, -5211854272861108819L, -1903131822648998119L, -8106986416796705681L,
    -5522047002568494197L, -2290872734783229842L, -8349324486880600507L, -5824969590173362730L,
    -2669525969289315508L, -8585982758446904049L, -6120792429631242157L, -3039304518611664792L,
    -8817094351773372351L, -6409681921289327535L, -3400416383184271515L, -9042789267131251553L,
    -6691800565486676537L, -3753064688430957767L, -79644842111309304L, -6967307053960650171L,
    -4097447799023424810L, -510123730351893109L, -7236356359111015049L, -4433759430461380907L,
    -930513269649338230L, -7499099821171918250L, -4762188758037509908L, -1341049929119499481L,
    -7755685233340769032L, -5082920523248573386L, -1741964635633328828L, -8006256924911912374L,
    -5396135137712502563L, -2133482903713240300L, -8250955842461857044L, -5702008784649933400L,
    -2515824962385028846L, -8489919629131724885L, -6000713517987268202L, -2889205879056697349L,
    -8723282702051517699L, -6292417359137009220L, -3253835680493873621L, -8951176327949752869L,
    -6577284391509803182L, -3609919470959866074L, -9173728696990998152L, -6855474852811359786L,
    -3957657547586811828L, -335385916056126881L, -7127145225176161157L, -4297245513042813542L,
    -759870872876129024L, -7392448323188662496L, -4628874385558440216L, -1174406963520662366L,
    -7651533379841495835L, -4952730706374481889L, -1579227364540714458L, -7904546130479028392L,
    -5268996644671397586L, -1974559787411859078L, -8151628894773493780L, -5577850100039479321L,
    -2360626606621961247L, -8392920656779807636L, -5879464802547371641L, -2737644984756826647L,
    -8628557143114098510L, -6174010410465235234L, -3105826994654156138L, -8858670899299929442L,
    -6461652605697523899L, -3465379738694516970L, -9083391364325154962L, -6742553186979055799L,
    -3816505465296431844L, -158945813193151901L, -7016870160886801794L, -4159401682681114339L,
    -587566084924005019L, -7284757830718584993L, -4494261269970843337L, -1006140569036166268L,
    -7546366883288685774L, -4821272585683469313L, -1414904713676948737L, -7801844473689174817L,
    -5140619573684080617L, -1814088448677712867L, -8051334308064652398L, -5452481866653427593L,
    -2203916314889396588L, -8294976724446954723L, -5757034887131305500L, -2584607590486743971L,
    -8532908771695296838L, -6054449946191733143L, -2956376414312278525L, -8765264286586255934L,
    -6344894339805432014L, -3319431906329402113L, -8992173969096958177L, -6628531442943809817L,
    -3673978285252374367L, -9213765455923815836L, -6905520801477381891L, -4020214983419339459L,
    -413582710846786420L, -7176018221920323369L, -4358336758973016307L, -836234930288882479L,
    -7440175859071633406L, -4688533805412153853L, -1248981238337804412L, -7698142301602209614L,
    -5010991858575374113L, -1652053804791829737L, -7950062655635975442L, -5325892301117581398L,
    -2045679357969588844L, -8196078626372074883L, -5633412264537705700L, -2430079312244744221L,
    -8436328597794046994L, -5933724728815170839L, -2805469892591575644L, -8670947710510816634L,
    -6226998619711132888L, -3172062256211528206L, -8900067937773286985L, -6513398903789220827L,
    -3530062611309138130L, -9123818159709293187L, -6793086681209228580L, -3879672333084147821L,
    -237904397927796872L, -7066219276345954901L, -4221088077005055722L, -664674077828931749L,
    -7332950326284164199L, -4554501889427817345L, -1081441343357383777L, -7593429867239446717L,
    -4880101315621920492L, -1488440626100012711L, -7847804418953589800L, -5198069505264599346L,
    -1885900863153361279L, -8096217067111932656L, -5508585315462527915L, -2274045625900771990L,
    -8338807543829064350L, -5811823411358942533L, -2653093245771290262L, -8575712306248138270L,
    -6107954364382784934L, -3023256937051093263L, -8807064613298015146L, -6397144748195131028L,
    -3384744916816525881L, -9032994600651410532L, -6679557232386875260L, -3737760522056206171L,
    -60514634142869810L, -6955350673980375487L, -4082502324048081455L, -491441886632713915L,
    -7224680206786528053L, -4419164240055772162L, -912269281642327298L, -7487697328667536418L,
    -4747935642407032618L, -1323233534581402868L, -7744549986754458649L, -5069001465015685407L,
    -1724565812842218855L, -7995382660667468640L, -5382542307406947896L, -2116491865831296966L,
    -8240336443785642460L, -5688734536304665171L, -2499232151953443560L, -8479549122611984081L,
    -5987750384837592197L, -2873001962619602342L, -8713155254278333320L, -6279758049420528746L,
    -3238011543348273028L, -8941286242233752499L, -6564921784364802720L, -3594466212028615495L,
    -9164070410158966541L, -6843401994271320272L, -3942566474411762436L, -316522074587315140L,
    -7115355324258153819L, -4282508136895304370L, -741449152691742558L, -7380934748073420955L,
    -4614482416664388289L, -1156417002403097458L, -7640289654143017767L, -4938676049251384305L,
    -1561659043136842477L, -7893565929601608404L, -5255271393574622601L, -1957403223540890347L,
    -8140906042354138323L, -5564446534515285000L, -2343872149716718346L, -8382449121214030822L,
    -5866375383090150624L, -2721283210435300376L, -8618331034163144591L, -6161227774276542835L,
    -3089848699418290639L, -8848684464777513506L, -6449169562544503978L, -3449775934753242068L,
    -9073638986861858149L, -6730362715149934782L, -3801267375510030573L, -139898200960150313L,
    -7004965403241175802L, -4144520735624081848L, -568964901102714406L, -7273132090830278360L,
    -4479729095110460046L, -987975350460687153L, -7535013621679011327L, -4807081008671376254L,
    -1397165242411832414L, -7790757304148477115L, -5126760611758208489L, -1796764746270372707L,
    -8040506994060064798L, -5438947724147693094L, -2186998636757228463L, -8284403175614349646L,
    -5743817951090549153L, -2568086420435798537L, -8522583040413455942L, -6041542782089432023L,
    -2940242459184402125L, -8755180564631333184L, -6332289687361778576L, -3303676090774835316L,
    -8982326584375353929L, -6616222212041804507L, -3658591746624867729L, -9204148869281624187L,
    -6893500068174642330L, -4005189066790915008L, -394800315061255856L, -7164279224554366766L,
    -4343663012265570553L, -817892746904575288L, -7428711994456441411L, -4674203974643163860L,
    -1231068949876566920L, -7686947121313936181L, -4996997883215032323L, -1634561335591402499L,
    -7939129862385708418L, -5312226309554747619L, -2028596868516046619L, -8185402070463610993L,
    -5620066569652125837L
  )
  /* Use the following code to generate `nibbles` in Scala REPL:
    val ns = new Array[Byte](256)
    java.util.Arrays.fill(ns, -1: Byte)
    ns('0') = 0
    ns('1') = 1
    ns('2') = 2
    ns('3') = 3
    ns('4') = 4
    ns('5') = 5
    ns('6') = 6
    ns('7') = 7
    ns('8') = 8
    ns('9') = 9
    ns('A') = 10
    ns('B') = 11
    ns('C') = 12
    ns('D') = 13
    ns('E') = 14
    ns('F') = 15
    ns('a') = 10
    ns('b') = 11
    ns('c') = 12
    ns('d') = 13
    ns('e') = 14
    ns('f') = 15
    ns.grouped(16).map(_.mkString(", ")).mkString("Array(\n", ",\n", "\n)")
   */
  private final val nibbles: Array[Byte] = Array(
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1, -1, -1, -1, -1, -1,
    -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
  )
  /* Use the following code to generate `base64Bytes` in Scala REPL:
    val bs = new Array[Byte](256)
    java.util.Arrays.fill(bs, -1: Byte)
    val ds = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    var i = 0
    while (i < ds.length) {
      bs(ds.charAt(i).toInt) = i.toByte
      i += 1
    }
    bs.grouped(16).map(_.mkString(", ")).mkString("Array(\n", ",\n", "\n)")
   */
  private final val base64Bytes: Array[Byte] = Array(
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
    52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1,
    -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
    15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
    -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
    41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
  )
  /* Use the following code to generate `base64UrlBytes` in Scala REPL:
    val bs = new Array[Byte](256)
    java.util.Arrays.fill(bs, -1: Byte)
    val ds = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    var i = 0
    while (i < ds.length) {
      bs(ds.charAt(i).toInt) = i.toByte
      i += 1
    }
    bs.grouped(16).map(_.mkString(", ")).mkString("Array(\n", ",\n", "\n)")
   */
  private final val base64UrlBytes: Array[Byte] = Array(
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1,
    52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1,
    -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
    15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63,
    -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
    41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
  )
  private final val zoneOffsets: Array[ZoneOffset] = new Array(145)
  private final val zoneIds: ConcurrentHashMap[Key, ZoneId] = new ConcurrentHashMap(256)
  private final val hexDigits: Array[Char] =
    Array('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
  /* Use the following code to generate `dumpBorder` in Scala REPL:
    "\n+----------+-------------------------------------------------+------------------+".toCharArray
      .grouped(16).map(_.map(_.toInt).mkString(", ")).mkString("Array(\n", ",\n", "\n)")
   */
  private final val dumpBorder: Array[Char] = Array(
    10, 43, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 43, 45, 45, 45,
    45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45,
    45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45,
    45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 43, 45,
    45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45,
    45, 43
  )
  /* Use the following code to generate `dumpHeader` in Scala REPL:
    "\n|          |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f | 0123456789abcdef |".toCharArray
      .grouped(16).map(_.map(_.toInt).mkString(", ")).mkString("Array(\n", ",\n", "\n)")
   */
  private final val dumpHeader: Array[Char] = Array(
    10, 124, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 124, 32, 32, 48,
    32, 32, 49, 32, 32, 50, 32, 32, 51, 32, 32, 52, 32, 32, 53, 32,
    32, 54, 32, 32, 55, 32, 32, 56, 32, 32, 57, 32, 32, 97, 32, 32,
    98, 32, 32, 99, 32, 32, 100, 32, 32, 101, 32, 32, 102, 32, 124, 32,
    48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 97, 98, 99, 100, 101, 102,
    32, 124
  )

  /**
    * The default math context used for rounding of `BigDecimal` values when parsing.
    */
  final val bigDecimalMathContext: MathContext = MathContext.DECIMAL128

  /**
    * The default limit for number of decimal digits in mantissa of parsed `BigDecimal` values.
    */
  final val bigDecimalDigitsLimit: Int = 308

  /**
    * The default limit for scale of parsed `BigDecimal` values.
    */
  final val bigDecimalScaleLimit: Int = 6178

  /**
    * The maximum number of digits in `BigInt` values.
    */
  final val bigIntDigitsLimit: Int = 308

  /**
    * Calculates hash code value string represented by sequence of characters from beginning of the provided char array
    * up to limit position.
    *
    * @param cs a char array
    * @param len an exclusive limit
    * @return a hash code value
    * @throws java.lang.NullPointerException if the `cs` is null
    * @throws java.lang.ArrayIndexOutOfBoundsException if the length of `cs` is less than the provided `len`
    */
  final def toHashCode(cs: Array[Char], len: Int): Int = {
    if (cs eq null) throw new NullPointerException
    if (cs.length < len) throw new ArrayIndexOutOfBoundsException(len)
    var h, i = 0
    while (i < len) {
      h = (h << 5) + (cs(i) - h)
      i += 1
    }
    h
  }
}

private class Key {
  private[this] var hash: Int = _
  private[this] var bs: Array[Byte] = _
  private[this] var from: Int = _
  private[this] var to: Int = _

  def set(hash: Int, bs: Array[Byte], from: Int, to: Int): Unit = {
    this.hash = hash
    this.bs = bs
    this.from = from
    this.to = to
  }

  def copy: Key = {
    val len = to - from
    val bs1 = new Array[Byte](len)
    System.arraycopy(bs, from, bs1, 0, len)
    val k = new Key
    k.set(hash, bs1, 0, len)
    k
  }

  override def hashCode: Int = hash

  override def equals(obj: Any): Boolean = {
    val k = obj.asInstanceOf[Key]
    val off = from
    val koff = k.fromIndex
    val len = to - off
    k.toIndex - koff == len && {
      val bs = this.bs
      val kbs = k.bytes
      var i = 0
      while (i < len && kbs(koff + i) == bs(off + i)) i += 1
      i == len
    }
  }

  override def toString: String = new String(bs, from, to - from)

  private def bytes: Array[Byte] = bs

  private def fromIndex: Int = from

  private def toIndex: Int = to
}