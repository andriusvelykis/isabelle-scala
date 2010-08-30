/*  Title:      Pure/General/symbol.scala
    Author:     Makarius

Detecting and recoding Isabelle symbols.
*/

package isabelle

import scala.io.Source
import scala.collection.mutable
import scala.util.matching.Regex


object Symbol
{
  /* spaces */

  val spc = ' '
  val space = " "

  private val static_spaces = space * 4000

  def spaces(k: Int): String =
  {
    require(k >= 0)
    if (k < static_spaces.length) static_spaces.substring(0, k)
    else space * k
  }


  /* Symbol regexps */

  private val plain = new Regex("""(?xs)
      [^\r\\ \ud800-\udfff] | [\ud800-\udbff][\udc00-\udfff] """)

  private val newline = new Regex("""(?xs) \r\n | \r """)

  private val symbol = new Regex("""(?xs)
      \\ < (?:
      \^? [A-Za-z][A-Za-z0-9_']* |
      \^raw: [\x20-\x7e\u0100-\uffff && [^.>]]* ) >""")

  // FIXME cover bad surrogates!?
  // FIXME check wrt. Isabelle/ML version
  private val bad_symbol = new Regex("(?xs) (?!" + symbol + ")" +
    """ \\ < (?: (?! \s | [\"`\\] | \(\* | \*\) | \{\* | \*\} ) . )*""")

  // total pattern
  val regex = new Regex(plain + "|" + newline + "|" + symbol + "|" + bad_symbol + "| .")


  /* basic matching */

  def is_plain(c: Char): Boolean = !(c == '\r' || c == '\\' || '\ud800' <= c && c <= '\udfff')

  def is_physical_newline(s: CharSequence): Boolean =
    "\n".contentEquals(s) || "\r".contentEquals(s) || "\r\n".contentEquals(s)

  def is_wellformed(s: CharSequence): Boolean =
    s.length == 1 && is_plain(s.charAt(0)) || !bad_symbol.pattern.matcher(s).matches

  class Matcher(text: CharSequence)
  {
    private val matcher = regex.pattern.matcher(text)
    def apply(start: Int, end: Int): Int =
    {
      require(0 <= start && start < end && end <= text.length)
      if (is_plain(text.charAt(start))) 1
      else {
        matcher.region(start, end).lookingAt
        matcher.group.length
      }
    }
  }


  /* iterator */

  def iterator(text: CharSequence) = new Iterator[CharSequence]
  {
    private val matcher = new Matcher(text)
    private var i = 0
    def hasNext = i < text.length
    def next = {
      val n = matcher(i, text.length)
      val s = text.subSequence(i, i + n)
      i += n
      s
    }
  }


  /* decoding offsets */

  class Index(text: CharSequence)
  {
    case class Entry(chr: Int, sym: Int)
    val index: Array[Entry] =
    {
      val matcher = new Matcher(text)
      val buf = new mutable.ArrayBuffer[Entry]
      var chr = 0
      var sym = 0
      while (chr < text.length) {
        val n = matcher(chr, text.length)
        chr += n
        sym += 1
        if (n > 1) buf += Entry(chr, sym)
      }
      buf.toArray
    }
    def decode(sym1: Int): Int =
    {
      val sym = sym1 - 1
      val end = index.length
      def bisect(a: Int, b: Int): Int =
      {
        if (a < b) {
          val c = (a + b) / 2
          if (sym < index(c).sym) bisect(a, c)
          else if (c + 1 == end || sym < index(c + 1).sym) c
          else bisect(c + 1, b)
        }
        else -1
      }
      val i = bisect(0, end)
      if (i < 0) sym
      else index(i).chr + sym - index(i).sym
    }
    def decode(range: Text.Range): Text.Range = range.map(decode(_))
  }


  /* recoding text */

  private class Recoder(list: List[(String, String)])
  {
    private val (min, max) =
    {
      var min = '\uffff'
      var max = '\u0000'
      for ((x, _) <- list) {
        val c = x(0)
        if (c < min) min = c
        if (c > max) max = c
      }
      (min, max)
    }
    private val table = Map[String, String]() ++ list
    def recode(text: String): String =
    {
      val len = text.length
      val matcher = regex.pattern.matcher(text)
      val result = new StringBuilder(len)
      var i = 0
      while (i < len) {
        val c = text(i)
        if (min <= c && c <= max) {
          matcher.region(i, len).lookingAt
          val x = matcher.group
          result.append(table.get(x) getOrElse x)
          i = matcher.end
        }
        else { result.append(c); i += 1 }
      }
      result.toString
    }
  }



  /** Symbol interpretation **/

  class Interpretation(symbol_decls: List[String])
  {
    /* read symbols */

    private val empty = new Regex("""(?xs) ^\s* (?: \#.* )? $ """)
    private val key = new Regex("""(?xs) (.+): """)

    private def read_decl(decl: String): (String, Map[String, String]) =
    {
      def err() = error("Bad symbol declaration: " + decl)

      def read_props(props: List[String]): Map[String, String] =
      {
        props match {
          case Nil => Map()
          case _ :: Nil => err()
          case key(x) :: y :: rest => read_props(rest) + (x -> y)
          case _ => err()
        }
      }
      decl.split("\\s+").toList match {
        case sym :: props if sym.length > 1 && is_wellformed(sym) => (sym, read_props(props))
        case _ => err()
      }
    }

    private val symbols: List[(String, Map[String, String])] =
      for (decl <- symbol_decls if !empty.pattern.matcher(decl).matches)
        yield read_decl(decl)


    /* misc properties */

    val names: Map[String, String] =
    {
      val name = new Regex("""\\<([A-Za-z][A-Za-z0-9_']*)>""")
      Map((for ((sym @ name(a), _) <- symbols) yield (sym -> a)): _*)
    }

    val abbrevs: Map[String, String] = Map((
      for ((sym, props) <- symbols if props.isDefinedAt("abbrev"))
        yield (sym -> props("abbrev"))): _*)


    /* main recoder methods */

    private val (decoder, encoder) =
    {
      val mapping =
        for {
          (sym, props) <- symbols
          val code =
            try { Integer.decode(props("code")).intValue }
            catch {
              case _: NoSuchElementException => error("Missing code for symbol " + sym)
              case _: NumberFormatException => error("Bad code for symbol " + sym)
            }
          val ch = new String(Character.toChars(code))
        } yield {
          if (code < 128) error("Illegal ASCII code for symbol " + sym)
          else (sym, ch)
        }
      (new Recoder(mapping),
       new Recoder(mapping map { case (x, y) => (y, x) }))
    }

    def decode(text: String): String = decoder.recode(text)
    def encode(text: String): String = encoder.recode(text)


    /* classification */

    private object Decode_Set
    {
      def apply(elems: String*): Set[String] =
      {
        val content = elems.toList
        Set((content ::: content.map(decode)): _*)
      }
    }

    private val letters = Decode_Set(
      "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
      "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
      "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
      "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",

      "\\<A>", "\\<B>", "\\<C>", "\\<D>", "\\<E>", "\\<F>", "\\<G>",
      "\\<H>", "\\<I>", "\\<J>", "\\<K>", "\\<L>", "\\<M>", "\\<N>",
      "\\<O>", "\\<P>", "\\<Q>", "\\<R>", "\\<S>", "\\<T>", "\\<U>",
      "\\<V>", "\\<W>", "\\<X>", "\\<Y>", "\\<Z>", "\\<a>", "\\<b>",
      "\\<c>", "\\<d>", "\\<e>", "\\<f>", "\\<g>", "\\<h>", "\\<i>",
      "\\<j>", "\\<k>", "\\<l>", "\\<m>", "\\<n>", "\\<o>", "\\<p>",
      "\\<q>", "\\<r>", "\\<s>", "\\<t>", "\\<u>", "\\<v>", "\\<w>",
      "\\<x>", "\\<y>", "\\<z>",

      "\\<AA>", "\\<BB>", "\\<CC>", "\\<DD>", "\\<EE>", "\\<FF>",
      "\\<GG>", "\\<HH>", "\\<II>", "\\<JJ>", "\\<KK>", "\\<LL>",
      "\\<MM>", "\\<NN>", "\\<OO>", "\\<PP>", "\\<QQ>", "\\<RR>",
      "\\<SS>", "\\<TT>", "\\<UU>", "\\<VV>", "\\<WW>", "\\<XX>",
      "\\<YY>", "\\<ZZ>", "\\<aa>", "\\<bb>", "\\<cc>", "\\<dd>",
      "\\<ee>", "\\<ff>", "\\<gg>", "\\<hh>", "\\<ii>", "\\<jj>",
      "\\<kk>", "\\<ll>", "\\<mm>", "\\<nn>", "\\<oo>", "\\<pp>",
      "\\<qq>", "\\<rr>", "\\<ss>", "\\<tt>", "\\<uu>", "\\<vv>",
      "\\<ww>", "\\<xx>", "\\<yy>", "\\<zz>",

      "\\<alpha>", "\\<beta>", "\\<gamma>", "\\<delta>", "\\<epsilon>",
      "\\<zeta>", "\\<eta>", "\\<theta>", "\\<iota>", "\\<kappa>",
      "\\<mu>", "\\<nu>", "\\<xi>", "\\<pi>", "\\<rho>", "\\<sigma>",
      "\\<tau>", "\\<upsilon>", "\\<phi>", "\\<chi>", "\\<psi>",
      "\\<omega>", "\\<Gamma>", "\\<Delta>", "\\<Theta>", "\\<Lambda>",
      "\\<Xi>", "\\<Pi>", "\\<Sigma>", "\\<Upsilon>", "\\<Phi>",
      "\\<Psi>", "\\<Omega>",

      "\\<^isub>", "\\<^isup>")

    private val blanks =
      Decode_Set(space, "\t", "\n", "\u000B", "\f", "\r", "\\<spacespace>", "\\<^newline>")

    private val sym_chars =
      Set("!", "#", "$", "%", "&", "*", "+", "-", "/", "<", "=", ">", "?", "@", "^", "_", "|", "~")

    def is_letter(sym: String): Boolean = letters.contains(sym)
    def is_digit(sym: String): Boolean = sym.length == 1 && '0' <= sym(0) && sym(0) <= '9'
    def is_quasi(sym: String): Boolean = sym == "_" || sym == "'"
    def is_letdig(sym: String): Boolean = is_letter(sym) || is_digit(sym) || is_quasi(sym)
    def is_blank(sym: String): Boolean = blanks.contains(sym)
    def is_symbolic_char(sym: String): Boolean = sym_chars.contains(sym)
    def is_symbolic(sym: String): Boolean = sym.startsWith("\\<") && !sym.startsWith("\\<^")
  }
}
