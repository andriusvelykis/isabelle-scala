/*  Title:      Pure/General/completion.scala
    Author:     Makarius

Semantic completion within the formal context (reported names).
Syntactic completion of keywords and symbols, with abbreviations
(based on language context).
*/

package isabelle


import scala.collection.immutable.SortedMap
import scala.util.parsing.combinator.RegexParsers
import scala.math.Ordering


object Completion
{
  /** completion result **/

  sealed case class Item(
    range: Text.Range,
    original: String,
    name: String,
    description: List[String],
    replacement: String,
    move: Int,
    immediate: Boolean)

  object Result
  {
    def empty(range: Text.Range): Result = Result(range, "", false, Nil)
  }

  sealed case class Result(
    range: Text.Range,
    original: String,
    unique: Boolean,
    items: List[Item])



  /** persistent history **/

  private val COMPLETION_HISTORY = Path.explode("$ISABELLE_HOME_USER/etc/completion_history")

  object History
  {
    val empty: History = new History()

    def load(): History =
    {
      def ignore_error(msg: String): Unit =
        System.err.println("### Ignoring bad content of file " + COMPLETION_HISTORY +
          (if (msg == "") "" else "\n" + msg))

      val content =
        if (COMPLETION_HISTORY.is_file) {
          try {
            import XML.Decode._
            list(pair(Symbol.decode_string, int))(
              YXML.parse_body(File.read(COMPLETION_HISTORY)))
          }
          catch {
            case ERROR(msg) => ignore_error(msg); Nil
            case _: XML.Error => ignore_error(""); Nil
          }
        }
        else Nil
      (empty /: content)(_ + _)
    }
  }

  final class History private(rep: SortedMap[String, Int] = SortedMap.empty)
  {
    override def toString: String = rep.mkString("Completion.History(", ",", ")")

    def frequency(name: String): Int = rep.getOrElse(name, 0)

    def + (entry: (String, Int)): History =
    {
      val (name, freq) = entry
      new History(rep + (name -> (frequency(name) + freq)))
    }

    def ordering: Ordering[Item] =
      new Ordering[Item] {
        def compare(item1: Item, item2: Item): Int =
        {
          frequency(item1.name) compare frequency(item2.name) match {
            case 0 => item1.name compare item2.name
            case ord => - ord
          }
        }
      }

    def save()
    {
      Isabelle_System.mkdirs(COMPLETION_HISTORY.dir)
      File.write_backup(COMPLETION_HISTORY,
        {
          import XML.Encode._
          YXML.string_of_body(list(pair(Symbol.encode_string, int))(rep.toList))
        })
    }
  }

  class History_Variable
  {
    private var history = History.empty
    def value: History = synchronized { history }

    def load()
    {
      val h = History.load()
      synchronized { history = h }
    }

    def update(item: Item, freq: Int = 1): Unit = synchronized {
      history = history + (item.name -> freq)
    }
  }



  /** semantic completion **/

  object Names
  {
    object Info
    {
      def unapply(info: Text.Markup): Option[Names] =
        info.info match {
          case XML.Elem(Markup(Markup.COMPLETION, _), body) =>
            try {
              val (total, names) =
              {
                import XML.Decode._
                pair(int, list(pair(string, pair(string, string))))(body)
              }
              Some(Names(info.range, total, names))
            }
            catch { case _: XML.Error => None }
          case XML.Elem(Markup(Markup.NO_COMPLETION, _), _) =>
            Some(Names(info.range, 0, Nil))
          case _ => None
        }
    }
  }

  sealed case class Names(
    range: Text.Range, total: Int, names: List[(String, (String, String))])
  {
    def no_completion: Boolean = total == 0 && names.isEmpty

    def complete(
      history: Completion.History,
      do_decode: Boolean,
      original: String): Option[Completion.Result] =
    {
      def decode(s: String): String = if (do_decode) Symbol.decode(s) else s
      val items =
        for {
          (xname, (kind, name)) <- names
          xname1 = decode(xname)
          if xname1 != original
          (full_name, descr_name) =
            if (kind == "") (name, quote(decode(name)))
            else (kind + "." + name, Library.plain_words(kind) + " " + quote(decode(name)))
          description = List(xname1, "(" + descr_name + ")")
        } yield Item(range, original, full_name, description, xname1, 0, true)

      if (items.isEmpty) None
      else Some(Result(range, original, names.length == 1, items.sorted(history.ordering)))
    }
  }



  /** syntactic completion **/

  /* language context */

  object Language_Context
  {
    val outer = Language_Context("", true, false)
    val inner = Language_Context(Markup.Language.UNKNOWN, true, false)
    val ML_outer = Language_Context(Markup.Language.ML, false, true)
    val ML_inner = Language_Context(Markup.Language.ML, true, false)
  }

  sealed case class Language_Context(language: String, symbols: Boolean, antiquotes: Boolean)
  {
    def is_outer: Boolean = language == ""
  }


  /* init */

  val empty: Completion = new Completion()
  def init(): Completion = empty.add_symbols()


  /* word parsers */

  private object Word_Parsers extends RegexParsers
  {
    override val whiteSpace = "".r

    private def reverse_symbol: Parser[String] = """>[A-Za-z0-9_']+\^?<\\""".r
    private def reverse_symb: Parser[String] = """[A-Za-z0-9_']{2,}\^?<\\""".r
    private def escape: Parser[String] = """[a-zA-Z0-9_']+\\""".r

    private val word_regex = "[a-zA-Z0-9_']+".r
    private def word: Parser[String] = word_regex
    private def word3: Parser[String] = "[a-zA-Z0-9_']{3,}".r
    private def underscores: Parser[String] = "_*".r

    def is_word(s: CharSequence): Boolean = word_regex.pattern.matcher(s).matches
    def is_word_char(c: Char): Boolean = Symbol.is_ascii_letdig(c)

    def extend_word(text: CharSequence, offset: Text.Offset): Text.Offset =
    {
      val n = text.length
      var i = offset
      while (i < n && is_word_char(text.charAt(i))) i += 1
      if (i < n && text.charAt(i) == '>' && read_symbol(text.subSequence(0, i + 1)).isDefined)
        i + 1
      else i
    }

    def read_symbol(in: CharSequence): Option[String] =
    {
      val reverse_in = new Library.Reverse(in)
      parse(reverse_symbol ^^ (_.reverse), reverse_in) match {
        case Success(result, _) => Some(result)
        case _ => None
      }
    }

    def read_word(explicit: Boolean, in: CharSequence): Option[(String, String)] =
    {
      val parse_word = if (explicit) word else word3
      val reverse_in = new Library.Reverse(in)
      val parser =
        (reverse_symbol | reverse_symb | escape) ^^ (x => (x.reverse, "")) |
        underscores ~ parse_word ^^ { case x ~ y => (y.reverse, x) }
      parse(parser, reverse_in) match {
        case Success(result, _) => Some(result)
        case _ => None
      }
    }
  }


  /* abbreviations */

  private val caret_indicator = '\007'
  private val antiquote = "@{"
  private val default_abbrs =
    Map("@{" -> "@{\007}", "`" -> "\\<open>\007\\<close>")
}

final class Completion private(
  keywords: Map[String, Boolean] = Map.empty,
  words_lex: Scan.Lexicon = Scan.Lexicon.empty,
  words_map: Multi_Map[String, String] = Multi_Map.empty,
  abbrevs_lex: Scan.Lexicon = Scan.Lexicon.empty,
  abbrevs_map: Multi_Map[String, (String, String)] = Multi_Map.empty)
{
  /* keywords */

  private def is_symbol(name: String): Boolean = Symbol.names.isDefinedAt(name)
  private def is_keyword(name: String): Boolean = !is_symbol(name) && keywords.isDefinedAt(name)
  private def is_keyword_template(name: String, template: Boolean): Boolean =
    is_keyword(name) && keywords(name) == template

  def + (keyword: String, template: String): Completion =
    new Completion(
      keywords + (keyword -> (keyword != template)),
      words_lex + keyword,
      words_map + (keyword -> template),
      abbrevs_lex,
      abbrevs_map)

  def + (keyword: String): Completion = this + (keyword, keyword)


  /* symbols with abbreviations */

  private def add_symbols(): Completion =
  {
    val words =
      (for ((x, _) <- Symbol.names.toList) yield (x, x)) :::
      (for ((x, y) <- Symbol.names.toList) yield ("\\" + y, x)) :::
      (for ((x, y) <- Symbol.abbrevs.toList if Completion.Word_Parsers.is_word(y)) yield (y, x))

    val symbol_abbrs =
      (for ((x, y) <- Symbol.abbrevs.iterator if !Completion.Word_Parsers.is_word(y))
        yield (y, x)).toList

    val abbrs =
      for ((a, b) <- symbol_abbrs ::: Completion.default_abbrs.toList)
        yield (a.reverse, (a, b))

    new Completion(
      keywords,
      words_lex ++ words.map(_._1),
      words_map ++ words,
      abbrevs_lex ++ abbrs.map(_._1),
      abbrevs_map ++ abbrs)
  }


  /* complete */

  def complete(
    history: Completion.History,
    do_decode: Boolean,
    explicit: Boolean,
    start: Text.Offset,
    text: CharSequence,
    caret: Int,
    extend_word: Boolean,
    language_context: Completion.Language_Context): Option[Completion.Result] =
  {
    def decode(s: String): String = if (do_decode) Symbol.decode(s) else s
    val length = text.length

    val abbrevs_result =
    {
      val reverse_in = new Library.Reverse(text.subSequence(0, caret))
      Scan.Parsers.parse(Scan.Parsers.literal(abbrevs_lex), reverse_in) match {
        case Scan.Parsers.Success(reverse_a, _) =>
          val abbrevs = abbrevs_map.get_list(reverse_a)
          abbrevs match {
            case Nil => None
            case (a, _) :: _ =>
              val ok =
                if (a == Completion.antiquote) language_context.antiquotes
                else language_context.symbols || Completion.default_abbrs.isDefinedAt(a)
              if (ok) Some(((a, abbrevs), caret))
              else None
          }
        case _ => None
      }
    }

    val words_result =
      if (abbrevs_result.isDefined) None
      else {
        val end =
          if (extend_word) Completion.Word_Parsers.extend_word(text, caret)
          else caret
        val result =
          Completion.Word_Parsers.read_symbol(text.subSequence(0, end)) match {
            case Some(symbol) => Some((symbol, ""))
            case None =>
              val word_context =
                end < length && Completion.Word_Parsers.is_word_char(text.charAt(end))
              if (word_context) None
              else Completion.Word_Parsers.read_word(explicit, text.subSequence(0, end))
          }
        result.map(
          {
            case (word, underscores) =>
              val complete_words = words_lex.completions(word)
              val full_word = word + underscores
              val completions =
                if (complete_words.contains(full_word) && is_keyword_template(full_word, false)) Nil
                else
                  for {
                    complete_word <- complete_words
                    ok =
                      if (is_keyword(complete_word)) language_context.is_outer
                      else language_context.symbols
                    if ok
                    completion <- words_map.get_list(complete_word)
                  } yield (complete_word, completion)
              (((full_word, completions), end))
          })
      }

    (abbrevs_result orElse words_result) match {
      case Some(((original, completions), end)) if !completions.isEmpty =>
        val range = Text.Range(- original.length, 0) + end + start
        val immediate =
          explicit ||
            (!Completion.Word_Parsers.is_word(original) &&
              Character.codePointCount(original, 0, original.length) > 1)
        val unique = completions.length == 1

        val items =
          for {
            (complete_word, name0) <- completions
            name1 = decode(name0)
            if name1 != original
            (s1, s2) =
              space_explode(Completion.caret_indicator, name1) match {
                case List(s1, s2) => (s1, s2)
                case _ => (name1, "")
              }
            move = - s2.length
            description =
              if (is_symbol(name0)) {
                if (name0 == name1) List(name0)
                else List(name1, "(symbol " + quote(name0) + ")")
              }
              else if (is_keyword_template(complete_word, true))
                List(name1, "(template " + quote(complete_word) + ")")
              else if (move != 0) List(name1, "(template)")
              else if (is_keyword(complete_word)) List(name1, "(keyword)")
              else List(name1)
          }
          yield Completion.Item(range, original, name1, description, s1 + s2, move, immediate)

        if (items.isEmpty) None
        else Some(Completion.Result(range, original, unique, items.sorted(history.ordering)))

      case _ => None
    }
  }
}
