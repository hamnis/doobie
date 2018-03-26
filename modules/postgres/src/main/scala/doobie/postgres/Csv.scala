package doobie.postgres

import doobie._

import cats.{ ContravariantSemigroupal, Foldable }
import cats.syntax.foldable._
import cats.instances.string._

import shapeless.{ HList, HNil, ::, <:!<, Generic }

/**
 * Typeclass for types that can be written as Postgres literal CSV. If you wish to implement an
 * instance it's worth reading the documentation at the link below.
 * @see [[https://www.postgresql.org/docs/9.6/static/sql-copy.html Postgres `COPY` command]]
 */
trait Csv[A] { outer =>

  /**
   * Construct an encoder for `A` that appends to the provided `StringBuilder.
   * @param a the value to encode
   * @param quote the `QUOTE` character used by the encoder
   * @param esc the `ESCAPE` character used by the encoder.
   */
  def unsafeEncode(a: A, quote: Char, esc: Char): StringBuilder => StringBuilder

  /** Encode `a` using the provided `QUOTE` and `ESCAPE` characters. */
  final def encode(a: A, quote: Char, esc: Char): String =
    unsafeEncode(a, quote, esc)(new StringBuilder).toString

  /** `Csv` is a contravariant functor. */
  final def contramap[B](f: B => A): Csv[B] =
    Csv.instance((b, q, e) => outer.unsafeEncode(f(b), q, e))

  /** `Csv` is semigroupal. */
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def product[B](fb: Csv[B]): Csv[(A, B)] =
    new Csv[(A, B)] {
      def unsafeEncode(ab: (A, B), q: Char, e: Char) = { sb =>
        outer.unsafeEncode(ab._1, q, e)(sb)
        sb.append(',')
        fb.unsafeEncode(ab._2, q, e)(sb)
      }
    }

}
object Csv extends CsvInstances {
  def apply[A](implicit ev: Csv[A]): ev.type = ev

  /**
   * Construct an instance, given a function matching the `unsafeEncode` signature.
   * @param f a function from `(A, QUOTE, ESCAPE) => StringBuilder => StringBuilder`
   */
  def instance[A](f: (A, Char, Char) => StringBuilder => StringBuilder): Csv[A] =
    new Csv[A] {
      def unsafeEncode(a: A, quote: Char, esc: Char) =
        sb => f(a, quote, esc)(sb)
    }

}

trait CsvInstances extends CsvInstances0 { this: Csv.type =>

  /** `Csv` is both contravariant and semigroupal. */
  implicit val CsvContravariantSemigroupal: ContravariantSemigroupal[Csv] =
    new ContravariantSemigroupal[Csv] {
      def contramap[A, B](fa: Csv[A])(f: B => A) = fa.contramap(f)
      def product[A, B](fa: Csv[A],fb: Csv[B]): Csv[(A, B)] = fa.product(fb)
    }

  // String encoder escapes any embedded `QUOTE` characters.
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements", "org.wartremover.warts.Equals"))
  implicit val stringInstance: Csv[String] =
    instance { (s, q, e) => sb =>
      sb.append(q)
      s.foreach { c =>
        if (c == q) sb.append(e)
        sb.append(c)
      }
      sb.append(q)
    }

  // Primitive Numerics
  implicit val intInstance:    Csv[Int]    = instance { (n, _, _) => _ append n }
  implicit val shortInstance:  Csv[Short]  = instance { (n, _, _) => _ append n }
  implicit val longInstance:   Csv[Long]   = instance { (n, _, _) => _ append n }
  implicit val floatInstance:  Csv[Float]  = instance { (n, _, _) => _ append n }
  implicit val doubleInstance: Csv[Double] = instance { (n, _, _) => _ append n }

  // Big Numerics
  implicit val bigDecimalInstance: Csv[BigDecimal] = instance { (n, _, _) => _ append n.toString }

  // Boolean
  implicit val booleanInstance: Csv[Boolean] =
    instance {
      case (true,  _, _) => _ append "TRUE"
      case (false, _, _) => _ append "FALSE"
    }

  // Date, Time, etc.

  // Byte arrays in \x01A3DD.. format.
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  implicit val byteArrayInstance: Csv[Array[Byte]] =
    instance { (bs, q, e) => sb =>
      sb.append("\\x")
      if (bs.length > 0) {
        val hex = BigInt(1, bs).toString(16)
        val pad = bs.length * 2 - hex.length
        (0 until pad).foreach(a => sb.append("0"))
        sb.append(hex)
      } else sb
    }

  // Any non-option Csv can be lifted to Option
  implicit def option[A](
    implicit csv: Csv[A],
             nope: A <:!< Option[X] forSome { type X }
  ): Csv[Option[A]] =
    instance {
      case (Some(a), q, e) => csv.unsafeEncode(a, q, e)
      case (None, q, e)    => identity // null is "" (!)
    }

  // HNil isn't a valid Csv but a single-element HList is
  implicit def single[A](
    implicit csv: Csv[A]
  ): Csv[A :: HNil] =
    csv.contramap(_.head)

  // HLists of more that one element
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  implicit def multiple[H, T <: HList](
    implicit h: Csv[H],
             t: Csv[T]
  ): Csv[H :: T] =
    (h product t).contramap(l => (l.head, l.tail))

  // Generic
  implicit def generic[A, B](
    implicit gen: Generic.Aux[A, B],
             csv: Csv[B]
  ): Csv[A] =
    csv.contramap(gen.to)

}

trait CsvInstances0 extends CsvInstances1 { this: Csv.type =>

  // Iterable and views thereof, as [nested] ARRAY
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements", "org.wartremover.warts.Var"))
  implicit def iterableInstance[F[_], A](
    implicit ev: Csv[A],
             f:  F[A] => Iterable[A]
  ): Csv[F[A]] =
    instance { (fa, q, e) => sb =>
      var first = true
      sb.append(q) // but only for the outermost array! inner ones aren't quoted
      sb.append("{")
      f(fa).foreach { a =>
        if (first) first = false
        else sb.append(',')
        ev.unsafeEncode(a, q, e)(sb)
      }
      sb.append('}')
      sb.append(q)
      sb
    }

}

trait CsvInstances1 { this: Csv.type =>

  // Foldable, not as fast
  implicit def foldableInstance[F[_]: Foldable, A](
    implicit ev: Csv[A]
  ): Csv[F[A]] =
    iterableInstance[List, A].contramap(_.toList)

}
