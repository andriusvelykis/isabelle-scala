/*  Title:      Pure/General/exn.scala
    Author:     Makarius

Support for exceptions (arbitrary throwables).
*/

package isabelle


object Exn
{
  /* runtime exceptions as values */

  sealed abstract class Result[A]
  case class Res[A](val result: A) extends Result[A]
  case class Exn[A](val exn: Throwable) extends Result[A]

  def capture[A](e: => A): Result[A] =
    try { Res(e) }
    catch { case exn: Throwable => Exn[A](exn) }

  def release[A](result: Result[A]): A =
    result match {
      case Res(x) => x
      case Exn(exn) => throw exn
    }
}

