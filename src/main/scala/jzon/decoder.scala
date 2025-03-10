package jzon

import scala.annotation._
import scala.collection.{ immutable, mutable }
import scala.util.control.NoStackTrace

import jzon.internal._

import Decoder.{ JsonError, UnsafeJson }

// convenience to match the circe api
object parser {

  /**
   * Attempts to decode the raw JSON string as an `A`.
   *
   * On failure a human readable message is returned using a jq friendly
   * format. For example the error
   * `.rows[0].elements[0].distance.value(missing)"` tells us the location of a
   * missing field named "value". We can use part of the error message in the
   * `jq` command line tool for further inspection, e.g.
   *
   * {{{jq '.rows[0].elements[0].distance' input.json}}}
   */
  def decode[A](str: CharSequence)(implicit D: Decoder[A]): Either[String, A] =
    D.decodeJson(str)
}

trait Decoder[A] { self =>
  // note that the string may not be fully consumed
  final def decodeJson(str: CharSequence): Either[String, A] = safely(new FastStringReader(str))
  final def decodeJson(bs: Array[Byte]): Either[String, A]   = decodeJson(new ByteArrayInput(bs))
  final def decodeJson(bs: Chunks): Either[String, A]        = decodeJson(new ChunksInput(bs))
  final def decodeJson(bs: ByteInput): Either[String, A]     = safely(new FastBytesReader(bs))
  final private[this] def safely(in: RetractReader): Either[String, A] =
    try Right(unsafeDecode(Nil, in))
    catch {
      case UnsafeJson(trace) => Left(JsonError.render(trace))
      case UnexpectedEnd     => Left("unexpected end of input")
    }

  // scalaz-deriving style MonadError combinators
  final def widen[B >: A]: Decoder[B]                 = self.asInstanceOf[Decoder[B]]
  final def xmap[B](f: A => B, g: B => A): Decoder[B] = map(f)
  final def map[B](f: A => B): Decoder[B] =
    new Decoder[B] {
      override def unsafeDecodeMissing(trace: List[JsonError]): B =
        f(self.unsafeDecodeMissing(trace))
      def unsafeDecode(trace: List[JsonError], in: RetractReader): B =
        f(self.unsafeDecode(trace, in))
    }
  final def emap[B](f: A => Either[String, B]): Decoder[B] =
    new Decoder[B] {
      override def unsafeDecodeMissing(trace: List[JsonError]): B =
        f(self.unsafeDecodeMissing(trace)) match {
          case Left(err) =>
            throw UnsafeJson(JsonError.Message(err) :: trace)
          case Right(b) => b
        }
      def unsafeDecode(trace: List[JsonError], in: RetractReader): B =
        f(self.unsafeDecode(trace, in)) match {
          case Left(err) =>
            throw UnsafeJson(JsonError.Message(err) :: trace)
          case Right(b) => b
        }
    }

  // The unsafe* methods are internal and should only be used by generated
  // decoders and web frameworks.
  //
  // They are unsafe because they are non-total and use mutable references.
  //
  // We could use a ReaderT[List[JsonError]] but that would bring in
  // dependencies and overhead, so we pass the trace context manually.
  def unsafeDecodeMissing(trace: List[JsonError]): A =
    throw UnsafeJson(JsonError.Message("missing") :: trace)

  def unsafeDecode(trace: List[JsonError], in: RetractReader): A
}

object Decoder extends DecoderGenerated with DecoderLowPriority1 with DecoderLowPriority2 {
  def apply[A](implicit a: Decoder[A]): Decoder[A] = a

  def derived[A, B](implicit S: shapely.Shapely[A, B], B: Decoder[B]): Decoder[A] = B.map(S.from)

  // Design note: we could require the position in the stream here to improve
  // debugging messages. But the cost would be that the RetractReader would need
  // to keep track and any wrappers would need to preserve the position. It may
  // still be desirable to do this but at the moment it is not necessary.
  final case class UnsafeJson(trace: List[JsonError])
      extends Exception("if you see this a dev made a mistake using Decoder")
      with NoStackTrace

  /* Allows a human readable string to be generated for decoding failures. */
  sealed abstract class JsonError
  object JsonError {
    def render(trace: List[JsonError]): String =
      trace.reverse.map {
        case Message(txt)        => s"($txt)"
        case ArrayAccess(i)      => s"[$i]"
        case ObjectAccess(field) => s".$field"
        case SumType(cons)       => s"{$cons}"
      }.mkString
    final case class Message(txt: String)        extends JsonError
    final case class ArrayAccess(i: Int)         extends JsonError
    final case class ObjectAccess(field: String) extends JsonError
    final case class SumType(cons: String)       extends JsonError
  }

  implicit val string: Decoder[String] = new Decoder[String] {
    def unsafeDecode(trace: List[JsonError], in: RetractReader): String =
      Lexer.string(trace, in).toString
  }
  implicit val boolean: Decoder[Boolean] = new Decoder[Boolean] {
    def unsafeDecode(trace: List[JsonError], in: RetractReader): Boolean =
      Lexer.boolean(trace, in)
  }

  implicit val char: Decoder[Char] = string.emap {
    case str if str.length == 1 => Right(str(0))
    case _                      => Left("expected one character")
  }
  implicit val symbol: Decoder[Symbol] = string.map(Symbol(_))

  implicit val byte: Decoder[Byte]   = number(Lexer.byte)
  implicit val short: Decoder[Short] = number(Lexer.short)
  implicit val int: Decoder[Int]     = number(Lexer.int)
  implicit val long: Decoder[Long]   = number(Lexer.long)
  implicit val biginteger: Decoder[java.math.BigInteger] = number(
    Lexer.biginteger
  )
  implicit val float: Decoder[Float]   = number(Lexer.float)
  implicit val double: Decoder[Double] = number(Lexer.double)
  implicit val bigdecimal: Decoder[java.math.BigDecimal] = number(
    Lexer.bigdecimal
  )
  // numbers decode from numbers or strings for maximum compatibility
  private[this] def number[A](
    f: (List[JsonError], RetractReader) => A
  ): Decoder[A] =
    new Decoder[A] {
      def unsafeDecode(trace: List[JsonError], in: RetractReader): A =
        (in.nextNonWhitespace(): @switch) match {
          case '"' =>
            val i = f(trace, in)
            Lexer.charOnly(trace, in, '"')
            i
          case _ =>
            in.retract()
            f(trace, in)
        }
    }

  // Option treats empty and null values as Nothing and passes values to the decoder.
  //
  // If alternative behaviour is desired, e.g. pass null to the underlying, then
  // use a newtype wrapper.
  implicit def option[A](implicit A: Decoder[A]): Decoder[Option[A]] =
    new Decoder[Option[A]] {
      private[this] val ull: Array[Char] = "ull".toCharArray
      override def unsafeDecodeMissing(trace: List[JsonError]): Option[A] =
        Option.empty
      def unsafeDecode(trace: List[JsonError], in: RetractReader): Option[A] =
        (in.nextNonWhitespace(): @switch) match {
          case 'n' =>
            Lexer.readChars(trace, in, ull, "null")
            None
          case _ =>
            in.retract()
            Some(A.unsafeDecode(trace, in))
        }
    }

  // supports multiple representations for compatibility with other libraries,
  // but does not support the "discriminator field" encoding with a field named
  // "value" used by some libraries.
  implicit def either[A, B](
    implicit
    A: Decoder[A],
    B: Decoder[B]
  ): Decoder[Either[A, B]] =
    new Decoder[Either[A, B]] {
      val names: Array[String] =
        Array("a", "Left", "left", "b", "Right", "right")
      val matrix: StringMatrix    = new StringMatrix(names)
      val spans: Array[JsonError] = names.map(JsonError.ObjectAccess(_))

      def unsafeDecode(
        trace: List[JsonError],
        in: RetractReader
      ): Either[A, B] = {
        Lexer.char(trace, in, '{')

        val values: Array[Any] = Array.ofDim(2)

        if (Lexer.firstObject(trace, in))
          while ({
            val field = Lexer.field(trace, in, matrix)
            if (field == -1) Lexer.skipValue(trace, in, null)
            else {
              val trace_ = spans(field) :: trace
              if (field < 3) {
                if (values(0) != null)
                  throw UnsafeJson(JsonError.Message("duplicate") :: trace_)
                values(0) = A.unsafeDecode(trace_, in)
              } else {
                if (values(1) != null)
                  throw UnsafeJson(JsonError.Message("duplicate") :: trace_)
                values(1) = B.unsafeDecode(trace_, in)
              }
            }
            Lexer.nextObject(trace, in)
          }) {}

        if (values(0) == null && values(1) == null)
          throw UnsafeJson(JsonError.Message("missing fields") :: trace)
        if (values(0) != null && values(1) != null)
          throw UnsafeJson(
            JsonError.Message("ambiguous either, both present") :: trace
          )
        if (values(0) != null)
          Left(values(0).asInstanceOf[A])
        else Right(values(1).asInstanceOf[B])
      }
    }

  private[jzon] def builder[A, T[_]](
    trace: List[JsonError],
    in: RetractReader,
    builder: mutable.ReusableBuilder[A, T[A]]
  )(implicit A: Decoder[A]): T[A] = {
    Lexer.char(trace, in, '[')
    var i: Int = 0
    if (Lexer.firstArray(trace, in)) while ({
      val trace_ = JsonError.ArrayAccess(i) :: trace
      builder += A.unsafeDecode(trace_, in)
      i += 1
      Lexer.nextArray(trace, in)
    }) {}
    builder.result()
  }

}

// We have a hierarchy of implicits for two reasons:
//
// 1. the compiler searches each scope and returns early if it finds a match.
//    This means that it is faster to put more complex derivation rules (that
//    are unlikely to be commonly used) into a lower priority scope, allowing
//    simple things like primitives to match fast.
//
// 2. sometimes we want to have overlapping instances with a more specific /
//    optimised instances, and a fallback for the more general case that would
//    otherwise conflict in a lower priority scope. A good example of this is to
//    have specialised decoders for collection types, falling back to BuildFrom.
private[jzon] trait DecoderLowPriority1 {
  implicit def list[A: Decoder]: Decoder[List[A]] = new Decoder[List[A]] {
    def unsafeDecode(trace: List[JsonError], in: RetractReader): List[A] =
      Decoder.builder(trace, in, new mutable.ListBuffer[A])
  }

  implicit def vector[A: Decoder]: Decoder[Vector[A]] = new Decoder[Vector[A]] {
    def unsafeDecode(trace: List[JsonError], in: RetractReader): Vector[A] =
      Decoder.builder(trace, in, new immutable.VectorBuilder[A]).toVector
  }

  implicit def seq[A: Decoder]: Decoder[Seq[A]] = list[A].widen

  // not implicit because this overlaps with decoders for lists of tuples
  def keylist[K, A](
    implicit
    K: FieldDecoder[K],
    A: Decoder[A]
  ): Decoder[List[(K, A)]] =
    new Decoder[List[(K, A)]] {
      def unsafeDecode(
        trace: List[JsonError],
        in: RetractReader
      ): List[(K, A)] = {
        val builder = new mutable.ListBuffer[(K, A)]
        Lexer.char(trace, in, '{')
        if (Lexer.firstObject(trace, in))
          while ({
            val field  = Lexer.string(trace, in).toString
            val trace_ = JsonError.ObjectAccess(field) :: trace
            Lexer.char(trace_, in, ':')
            val value = A.unsafeDecode(trace_, in)
            builder += ((K.unsafeDecodeField(trace_, field), value))
            Lexer.nextObject(trace, in)
          }) {}
        builder.result()
      }
    }

  implicit def sortedmap[K: FieldDecoder: Ordering, V: Decoder]: Decoder[collection.SortedMap[K, V]] =
    keylist[K, V].map(lst => collection.SortedMap.apply(lst: _*))

  implicit def map[K: FieldDecoder, V: Decoder]: Decoder[Map[K, V]] = hashmap[K, V].widen

  implicit def hashmap[K: FieldDecoder, V: Decoder]: Decoder[immutable.HashMap[K, V]] =
    keylist[K, V].map(lst => immutable.HashMap(lst: _*))

  implicit def set[A: Decoder]: Decoder[Set[A]] = hashset[A].widen
  implicit def hashset[A: Decoder]: Decoder[immutable.HashSet[A]] =
    list[A].map(lst => immutable.HashSet(lst: _*))
  implicit def sortedset[A: Ordering: Decoder]: Decoder[immutable.SortedSet[A]] =
    list[A].map(lst => immutable.SortedSet(lst: _*))

}

private[jzon] trait DecoderLowPriority2 {

  implicit def field[A](implicit A: FieldDecoder[A]): Decoder[A] = new Decoder[A] {
    override def unsafeDecode(trace: List[JsonError], in: RetractReader): A = {
      val str = Decoder.string.unsafeDecode(trace, in)
      A.unsafeDecodeField(trace, str)
    }
  }

}

/** When decoding a JSON Object, we only allow the keys that implement this interface. */
trait FieldDecoder[A] { self =>
  final def widen[B >: A]: FieldDecoder[B] =
    self.asInstanceOf[FieldDecoder[B]]
  final def xmap[B](f: A => B, g: B => A): FieldDecoder[B] = map(f)
  final def map[B](f: A => B): FieldDecoder[B] =
    new FieldDecoder[B] {
      def unsafeDecodeField(trace: List[JsonError], in: String): B =
        f(self.unsafeDecodeField(trace, in))
    }
  final def emap[B](f: A => Either[String, B]): FieldDecoder[B] =
    new FieldDecoder[B] {
      def unsafeDecodeField(trace: List[JsonError], in: String): B =
        f(self.unsafeDecodeField(trace, in)) match {
          case Left(err) =>
            throw UnsafeJson(JsonError.Message(err) :: trace)
          case Right(b) => b
        }
    }

  def unsafeDecodeField(trace: List[JsonError], in: String): A
}
object FieldDecoder {
  def apply[A](implicit A: FieldDecoder[A]): FieldDecoder[A] = A

  implicit val string: FieldDecoder[String] = new FieldDecoder[String] {
    def unsafeDecodeField(trace: List[JsonError], in: String): String = in
  }
}

// Common code that is mixed into all the generated CaseClass decoders.
//
// Ostensibly, we could add a fully typesafe version of this code to every
// decoder, but it would be less efficient because we would have to store values
// as Option since we cannot null them out. But by erasing everything into an
// Array[Any] we get around that and avoid the object allocation.
private[jzon] abstract class CaseClassDecoder[A, CC <: shapely.CaseClass[A]](M: shapely.Meta[A]) extends Decoder[CC] {
  val no_extra = M.annotations.collectFirst { case _: no_extra_fields => () }.isDefined

  val names: Array[String] = M.fieldAnnotations
    .zip(M.fieldNames)
    .map {
      case (a, n) => a.collectFirst { case field(name) => name }.getOrElse(n)
    }
    .toArray

  val matrix: StringMatrix    = new StringMatrix(names)
  val spans: Array[JsonError] = names.map(JsonError.ObjectAccess(_))

  def tcs: Array[Decoder[Any]]
  def cons(ps: Array[Any]): CC

  final override def unsafeDecode(trace: List[JsonError], in: RetractReader): CC = {
    Lexer.char(trace, in, '{')

    val ps: Array[Any] = Array.ofDim(names.length)
    if (Lexer.firstObject(trace, in))
      while ({
        var trace_ = trace
        val field  = Lexer.field(trace, in, matrix)
        if (field != -1) {
          trace_ = spans(field) :: trace
          if (ps(field) != null)
            throw UnsafeJson(JsonError.Message("duplicate") :: trace_)
          ps(field) = tcs(field).unsafeDecode(trace_, in)
        } else if (no_extra) {
          throw UnsafeJson(
            JsonError.Message(s"invalid extra field") :: trace
          )
        } else
          Lexer.skipValue(trace_, in, null)

        Lexer.nextObject(trace, in)
      }) {}

    var i = 0
    while (i < names.length) {
      if (ps(i) == null)
        ps(i) = tcs(i).unsafeDecodeMissing(spans(i) :: trace)
      i += 1
    }

    cons(ps)
  }
}

private[jzon] abstract class SealedTraitDecoder[A, ST <: shapely.SealedTrait[A]](subs: Array[shapely.Meta[_]])
    extends Decoder[ST] {
  val names: Array[String]    = subs.map(m => m.annotations.collectFirst { case hint(name) => name }.getOrElse(m.name))
  val matrix: StringMatrix    = new StringMatrix(names)
  val spans: Array[JsonError] = names.map(JsonError.ObjectAccess(_))

  def tcs: Array[Decoder[ST]]

  // not allowing extra fields in this encoding
  def unsafeDecode(trace: List[JsonError], in: RetractReader): ST = {
    Lexer.char(trace, in, '{')
    if (Lexer.firstObject(trace, in)) {
      val field = Lexer.field(trace, in, matrix)
      if (field != -1) {
        val trace_ = spans(field) :: trace
        val a      = tcs(field).unsafeDecode(trace_, in)
        Lexer.char(trace, in, '}')
        return a
      } else
        throw UnsafeJson(JsonError.Message("invalid disambiguator") :: trace)
    } else
      throw UnsafeJson(JsonError.Message("expected non-empty object") :: trace)
  }
}

private[jzon] abstract class SealedTraitDiscrimDecoder[A, ST <: shapely.SealedTrait[A]](
  subs: Array[shapely.Meta[_]],
  hintfield: String
) extends Decoder[ST] {
  val names: Array[String] = subs.map(m => m.annotations.collectFirst { case hint(name) => name }.getOrElse(m.name))
  val matrix: StringMatrix = new StringMatrix(names)

  def tcs: Array[Decoder[ST]]

  def unsafeDecode(trace: List[JsonError], in: RetractReader): ST = {
    var fields: List[(CharSequence, CharSequence)] = Nil
    var hint: Int                                  = -1

    Lexer.char(trace, in, '{')
    if (Lexer.firstObject(trace, in))
      while ({
        // materialise the string since we don't know what it can be
        val field = Lexer.string(trace, in)
        Lexer.char(trace, in, ':')

        // an additional performance / security improvement that could be made
        // here would be to assume that the case classes are all derived (which
        // could be checked by testing the type) and then use the subs to find
        // which field names are valid to retain.
        if (hintfield.contentEquals(field)) {
          if (hint != -1)
            throw UnsafeJson(JsonError.Message(s"duplicate disambiguator '$hintfield'") :: trace)
          hint = Lexer.ordinal(trace, in, matrix)
          if (hint == -1)
            throw UnsafeJson(JsonError.Message(s"invalid disambiguator in '$hintfield'") :: trace)
        } else {
          val out = new FastStringWriter(1024)
          Lexer.skipValue(trace, in, out)
          fields ::= (field -> out.buffer) // duplicates will be caught later
        }
        Lexer.nextObject(trace, in)
      }) {}

    if (hint == -1)
      throw UnsafeJson(JsonError.Message(s"missing disambiguator '$hintfield'") :: trace)

    val reconstructed = new FastStringWriter(1024)
    reconstructed.append("{")
    var first = true
    fields.foreach {
      case (name, value) =>
        if (first) first = false
        else reconstructed.append(',')
        Encoder.charseq.unsafeEncode(name, None, reconstructed)
        reconstructed.append(':')
        reconstructed.append(value)
    }
    reconstructed.append("}")

    tcs(hint).unsafeDecode(trace, new FastStringReader(reconstructed.buffer))
  }
}
