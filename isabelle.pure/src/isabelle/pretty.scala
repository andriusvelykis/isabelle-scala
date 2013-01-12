/*  Title:      Pure/General/pretty.scala
    Author:     Makarius

Generic pretty printing module.
*/

package isabelle


import java.awt.FontMetrics


object Pretty
{
  /* spaces */

  val space = " "

  private val static_spaces = space * 4000

  def spaces(k: Int): String =
  {
    require(k >= 0)
    if (k < static_spaces.length) static_spaces.substring(0, k)
    else space * k
  }


  /* markup trees with physical blocks and breaks */

  def block(body: XML.Body): XML.Tree = Block(2, body)

  object Block
  {
    def apply(i: Int, body: XML.Body): XML.Tree =
      XML.Elem(Markup(Markup.BLOCK, Markup.Indent(i)), body)

    def unapply(tree: XML.Tree): Option[(Int, XML.Body)] =
      tree match {
        case XML.Elem(Markup(Markup.BLOCK, Markup.Indent(i)), body) =>
          Some((i, body))
        case _ => None
      }
  }

  object Break
  {
    def apply(w: Int): XML.Tree =
      XML.Elem(Markup(Markup.BREAK, Markup.Width(w)),
        List(XML.Text(spaces(w))))

    def unapply(tree: XML.Tree): Option[Int] =
      tree match {
        case XML.Elem(Markup(Markup.BREAK, Markup.Width(w)), _) => Some(w)
        case _ => None
      }
  }

  val FBreak = XML.Text("\n")

  val Separator = List(XML.elem(Markup.SEPARATOR, List(XML.Text(space))), FBreak)
  def separate(ts: List[XML.Tree]): XML.Body = Library.separate(Separator, ts.map(List(_))).flatten


  /* formatted output */

  def standard_format(body: XML.Body): XML.Body =
    body flatMap {
      case XML.Wrapped_Elem(markup, body1, body2) =>
        List(XML.Wrapped_Elem(markup, body1, standard_format(body2)))
      case XML.Elem(markup, body) => List(XML.Elem(markup, standard_format(body)))
      case XML.Text(text) => Library.separate(FBreak, split_lines(text).map(XML.Text))
    }

  private sealed case class Text(tx: XML.Body = Nil, val pos: Double = 0.0, val nl: Int = 0)
  {
    def newline: Text = copy(tx = FBreak :: tx, pos = 0.0, nl = nl + 1)
    def string(s: String, len: Double): Text = copy(tx = XML.Text(s) :: tx, pos = pos + len)
    def blanks(wd: Int): Text = string(spaces(wd), wd.toDouble)
    def content: XML.Body = tx.reverse
  }

  private val margin_default = 76
  private def metric_default(s: String) = s.length.toDouble

  def char_width(metrics: FontMetrics): Double = metrics.stringWidth("mix").toDouble / 3
  def char_width_int(metrics: FontMetrics): Int = char_width(metrics).round.toInt

  def font_metric(metrics: FontMetrics): String => Double =
    if (metrics == null) ((s: String) => s.length.toDouble)
    else {
      val unit = char_width(metrics)
      ((s: String) => if (s == "\n") 1.0 else metrics.stringWidth(s) / unit)
    }

  def formatted(input: XML.Body, margin: Int = margin_default,
    metric: String => Double = metric_default): XML.Body =
  {
    val breakgain = margin / 20
    val emergencypos = margin / 2

    def content_length(tree: XML.Tree): Double =
      XML.traverse_text(List(tree))(0.0)(_ + metric(_))

    def breakdist(trees: XML.Body, after: Double): Double =
      trees match {
        case Break(_) :: _ => 0.0
        case FBreak :: _ => 0.0
        case t :: ts => content_length(t) + breakdist(ts, after)
        case Nil => after
      }

    def forcenext(trees: XML.Body): XML.Body =
      trees match {
        case Nil => Nil
        case FBreak :: _ => trees
        case Break(_) :: ts => FBreak :: ts
        case t :: ts => t :: forcenext(ts)
      }

    def format(trees: XML.Body, blockin: Double, after: Double, text: Text): Text =
      trees match {
        case Nil => text

        case Block(indent, body) :: ts =>
          val pos1 = text.pos + indent
          val pos2 = pos1 % emergencypos
          val blockin1 =
            if (pos1 < emergencypos) pos1
            else pos2
          val btext = format(body, blockin1, breakdist(ts, after), text)
          val ts1 = if (text.nl < btext.nl) forcenext(ts) else ts
          format(ts1, blockin, after, btext)

        case Break(wd) :: ts =>
          if (text.pos + wd <= (margin - breakdist(ts, after)).max(blockin + breakgain))
            format(ts, blockin, after, text.blanks(wd))
          else format(ts, blockin, after, text.newline.blanks(blockin.toInt))
        case FBreak :: ts => format(ts, blockin, after, text.newline.blanks(blockin.toInt))

        case XML.Wrapped_Elem(markup, body1, body2) :: ts =>
          val btext = format(body2, blockin, breakdist(ts, after), text.copy(tx = Nil))
          val ts1 = if (text.nl < btext.nl) forcenext(ts) else ts
          val btext1 = btext.copy(tx = XML.Wrapped_Elem(markup, body1, btext.content) :: text.tx)
          format(ts1, blockin, after, btext1)

        case XML.Elem(markup, body) :: ts =>
          val btext = format(body, blockin, breakdist(ts, after), text.copy(tx = Nil))
          val ts1 = if (text.nl < btext.nl) forcenext(ts) else ts
          val btext1 = btext.copy(tx = XML.Elem(markup, btext.content) :: text.tx)
          format(ts1, blockin, after, btext1)

        case XML.Text(s) :: ts => format(ts, blockin, after, text.string(s, metric(s)))
      }

    format(standard_format(input), 0.0, 0.0, Text()).content
  }

  def string_of(input: XML.Body, margin: Int = margin_default,
      metric: String => Double = metric_default): String =
    XML.content(formatted(input, margin, metric))


  /* unformatted output */

  def unformatted(input: XML.Body): XML.Body =
  {
    def fmt(tree: XML.Tree): XML.Body =
      tree match {
        case Block(_, body) => body.flatMap(fmt)
        case Break(wd) => List(XML.Text(spaces(wd)))
        case FBreak => List(XML.Text(space))
        case XML.Wrapped_Elem(markup, body1, body2) =>
          List(XML.Wrapped_Elem(markup, body1, body2.flatMap(fmt)))
        case XML.Elem(markup, body) => List(XML.Elem(markup, body.flatMap(fmt)))
        case XML.Text(_) => List(tree)
      }
    standard_format(input).flatMap(fmt)
  }

  def str_of(input: XML.Body): String = XML.content(unformatted(input))
}
