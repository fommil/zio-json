package zio.json

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import zio.json

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import io.circe
import zio.json.SyntheticBenchmarks._
import zio.json.internal.TestUtils._
import org.openjdk.jmh.annotations._
import play.api.libs.{ json => Play }

import scala.util.Try

final case class Nested(n: Option[Nested])
object Nested {
  implicit val zioJsonDecoder: json.Decoder[Nested] =
    json.Decoder.derived
  implicit val zioJsonEncoder: json.Encoder[Nested] =
    json.Encoder.derived

  implicit val customConfig: circe.generic.extras.Configuration =
    circe.generic.extras.Configuration.default
      .copy(discriminator = Some("type"))
  implicit lazy val circeDecoder: circe.Decoder[Nested] =
    circe.generic.extras.semiauto.deriveConfiguredDecoder[Nested]
  implicit lazy val circeEncoder: circe.Encoder[Nested] =
    circe.generic.extras.semiauto.deriveConfiguredEncoder[Nested]

  implicit lazy val playFormatter: Play.Format[Nested] =
    Play.Json.format[Nested]

}

@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
class SyntheticBenchmarks {
  //@Param(Array("100", "1000"))
  var size: Int              = 500
  var jsonString: String     = _
  var jsonBytes: Array[Byte] = _
  var decoded: Nested        = _

  @Setup
  def setup(): Unit = {
    val obj = 1.to(size).foldLeft(Nested(None))((n, _) => Nested(Some(n)))

    jsonString = {
      import circe.syntax._

      obj.asJson.noSpaces
    }
    jsonBytes = asBytes(jsonString)

    decoded = circe.parser.decode[Nested](jsonString).toOption.get

    assert(decodeJsoniterSuccess() == decodeZioSuccess())

    assert(decodeCirceSuccess() == decodeZioSuccess())

    assert(decodePlaySuccess() == decodeZioSuccess())
  }

  @Benchmark
  def decodeJsoniterSuccess(): Either[String, Nested] =
    Try(readFromArray(jsonString.getBytes(UTF_8)))
      .fold(t => Left(t.toString), Right.apply)

  @Benchmark
  def decodeCirceSuccess(): Either[circe.Error, Nested] =
    circe.parser.decode[Nested](jsonString)

  @Benchmark
  def encodeCirce(): String = {
    import io.circe.syntax._

    decoded.asJson.noSpaces
  }

  @Benchmark
  def decodePlaySuccess(): Either[String, Nested] =
    Try(Play.Json.parse(jsonString).as[Nested])
      .fold(t => Left(t.toString), Right.apply)

  @Benchmark
  def encodePlay(): String =
    Play.Json.stringify(implicitly[Play.Writes[Nested]].writes(decoded))

  @Benchmark
  def decodeZioSuccess(): Either[String, Nested] =
    json.Decoder[Nested].decodeJson(jsonBytes)

  @Benchmark
  def encodeZio(): String = {
    import zio.json.syntax._

    decoded.toJson
  }

}

object SyntheticBenchmarks {
  implicit val codec: JsonValueCodec[Nested] =
    JsonCodecMaker.make(CodecMakerConfig.withAllowRecursiveTypes(true))
}
