/*  Title:      Pure/Isar/outer_syntax.scala
    Author:     Makarius

Isabelle/Isar outer syntax.
*/

package isabelle


import scala.util.parsing.input.{Reader, CharSequenceReader}
import scala.collection.mutable


object Outer_Syntax
{
  def quote_string(str: String): String =
  {
    val result = new StringBuilder(str.length + 10)
    result += '"'
    for (s <- Symbol.iterator(str)) {
      if (s.length == 1) {
        val c = s(0)
        if (c < 32 && c != YXML.X && c != YXML.Y || c == '\\' || c == '"') {
          result += '\\'
          if (c < 10) result += '0'
          if (c < 100) result += '0'
          result ++= (c.asInstanceOf[Int].toString)
        }
        else result += c
      }
      else result ++= s
    }
    result += '"'
    result.toString
  }

  val empty: Outer_Syntax = new Outer_Syntax()

  def init(): Outer_Syntax = new Outer_Syntax(completion = Completion.init())

  def init_pure(): Outer_Syntax =
    init() + ("theory", Keyword.THY_BEGIN) + ("ML_file", Keyword.THY_LOAD)
}

final class Outer_Syntax private(
  keywords: Map[String, (String, List[String])] = Map.empty,
  lexicon: Scan.Lexicon = Scan.Lexicon.empty,
  val completion: Completion = Completion.empty)
{
  override def toString: String =
    (for ((name, (kind, files)) <- keywords) yield {
      if (kind == Keyword.MINOR) quote(name)
      else
        quote(name) + " :: " + quote(kind) +
        (if (files.isEmpty) "" else " (" + commas_quote(files) + ")")
    }).toList.sorted.mkString("keywords\n  ", " and\n  ", "")

  def keyword_kind_files(name: String): Option[(String, List[String])] = keywords.get(name)
  def keyword_kind(name: String): Option[String] = keyword_kind_files(name).map(_._1)

  def thy_load_commands: List[(String, List[String])] =
    (for ((name, (Keyword.THY_LOAD, files)) <- keywords.iterator) yield (name, files)).toList

  def + (name: String, kind: (String, List[String]), replace: Option[String]): Outer_Syntax =
    new Outer_Syntax(
      keywords + (name -> kind),
      lexicon + name,
      if (Keyword.control(kind._1) || replace == Some("")) completion
      else completion + (name, replace getOrElse name))

  def + (name: String, kind: (String, List[String])): Outer_Syntax = this + (name, kind, Some(name))
  def + (name: String, kind: String): Outer_Syntax = this + (name, (kind, Nil), Some(name))
  def + (name: String, replace: Option[String]): Outer_Syntax =
    this + (name, (Keyword.MINOR, Nil), replace)
  def + (name: String): Outer_Syntax = this + (name, None)

  def add_keywords(keywords: Thy_Header.Keywords): Outer_Syntax =
    (this /: keywords) {
      case (syntax, ((name, Some((kind, _)), replace))) =>
        syntax +
          (Symbol.decode(name), kind, replace) +
          (Symbol.encode(name), kind, replace)
      case (syntax, ((name, None, replace))) =>
        syntax +
          (Symbol.decode(name), replace) +
          (Symbol.encode(name), replace)
    }

  def is_command(name: String): Boolean =
    keyword_kind(name) match {
      case Some(kind) => kind != Keyword.MINOR
      case None => false
    }

  def heading_level(name: String): Option[Int] =
  {
    keyword_kind(name) match {
      case _ if name == "header" => Some(0)
      case Some(Keyword.THY_HEADING1) => Some(1)
      case Some(Keyword.THY_HEADING2) | Some(Keyword.PRF_HEADING2) => Some(2)
      case Some(Keyword.THY_HEADING3) | Some(Keyword.PRF_HEADING3) => Some(3)
      case Some(Keyword.THY_HEADING4) | Some(Keyword.PRF_HEADING4) => Some(4)
      case Some(kind) if Keyword.theory(kind) => Some(5)
      case _ => None
    }
  }

  def heading_level(command: Command): Option[Int] =
    heading_level(command.name)


  /* tokenize */

  def scan(input: Reader[Char]): List[Token] =
    Exn.recover  // FIXME !?
    {
      import lexicon._

      parseAll(rep(token(is_command)), input) match {
        case Success(tokens, _) => tokens
        case _ => error("Unexpected failure of tokenizing input:\n" + input.source.toString)
      }
    }

  def scan(input: CharSequence): List[Token] =
    scan(new CharSequenceReader(input))

  def scan_context(input: CharSequence, context: Scan.Context): (List[Token], Scan.Context) =
    Exn.recover  // FIXME !?
    {
      import lexicon._

      var in: Reader[Char] = new CharSequenceReader(input)
      val toks = new mutable.ListBuffer[Token]
      var ctxt = context
      while (!in.atEnd) {
        parse(token_context(is_command, ctxt), in) match {
          case Success((x, c), rest) => { toks += x; ctxt = c; in = rest }
          case NoSuccess(_, rest) =>
            error("Unexpected failure of tokenizing input:\n" + rest.source.toString)
        }
      }
      (toks.toList, ctxt)
    }
}
