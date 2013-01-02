/*  Title:      Tools/jEdit/src/token_markup.scala
    Author:     Makarius

Outer syntax token markup.
*/

package isabelle.jedit


import isabelle._

import java.awt.{Font, Color}
import java.awt.font.{TextAttribute, TransformAttribute, FontRenderContext, LineMetrics}
import java.awt.geom.AffineTransform

import org.gjt.sp.util.SyntaxUtilities
import org.gjt.sp.jedit.{jEdit, Mode}
import org.gjt.sp.jedit.syntax.{Token => JEditToken, TokenMarker, TokenHandler,
  ParserRuleSet, ModeProvider, XModeHandler, SyntaxStyle}
import org.gjt.sp.jedit.textarea.{TextArea, Selection}
import org.gjt.sp.jedit.buffer.JEditBuffer

import javax.swing.text.Segment


object Token_Markup
{
  /* editing support for control symbols */

  val is_control_style =
    Set(Symbol.sub_decoded, Symbol.sup_decoded,
      Symbol.isub_decoded, Symbol.isup_decoded, Symbol.bold_decoded)

  def update_control_style(control: String, text: String): String =
  {
    val result = new StringBuilder
    for (sym <- Symbol.iterator(text) if !is_control_style(sym)) {
      if (Symbol.is_controllable(sym)) result ++= control
      result ++= sym
    }
    result.toString
  }

  def edit_control_style(text_area: TextArea, control: String)
  {
    Swing_Thread.assert()

    val buffer = text_area.getBuffer

    text_area.getSelection.foreach(sel => {
      val before = JEdit_Lib.point_range(buffer, sel.getStart - 1)
      JEdit_Lib.try_get_text(buffer, before) match {
        case Some(s) if is_control_style(s) =>
          text_area.extendSelection(before.start, before.stop)
        case _ =>
      }
    })

    text_area.getSelection.toList match {
      case Nil =>
        text_area.setSelectedText(control)
      case sels =>
        JEdit_Lib.buffer_edit(buffer) {
          sels.foreach(sel =>
            text_area.setSelectedText(sel,
              update_control_style(control, text_area.getSelectedText(sel))))
        }
    }
  }


  /* font operations */

  private def font_metrics(font: Font): LineMetrics =
    font.getLineMetrics("", new FontRenderContext(null, false, false))

  private def imitate_font(family: String, font: Font): Font =
  {
    val font1 = new Font (family, font.getStyle, font.getSize)
    font1.deriveFont(font_metrics(font).getAscent / font_metrics(font1).getAscent * font.getSize)
  }

  private def transform_font(font: Font, transform: AffineTransform): Font =
  {
    import scala.collection.JavaConversions._
    font.deriveFont(Map(TextAttribute.TRANSFORM -> new TransformAttribute(transform)))
  }


  /* extended syntax styles */

  private val plain_range: Int = JEditToken.ID_COUNT
  private val full_range = 6 * plain_range + 1
  private def check_range(i: Int) { require(0 <= i && i < plain_range) }

  def subscript(i: Byte): Byte = { check_range(i); (i + plain_range).toByte }
  def superscript(i: Byte): Byte = { check_range(i); (i + 2 * plain_range).toByte }
  def bold(i: Byte): Byte = { check_range(i); (i + 3 * plain_range).toByte }
  def user_font(idx: Int, i: Byte): Byte = { check_range(i); (i + (4 + idx) * plain_range).toByte }
  val hidden: Byte = (6 * plain_range).toByte

  private def font_style(style: SyntaxStyle, f: Font => Font): SyntaxStyle =
    new SyntaxStyle(style.getForegroundColor, style.getBackgroundColor, f(style.getFont))

  private def script_style(style: SyntaxStyle, i: Int): SyntaxStyle =
  {
    font_style(style, font0 =>
      {
        import scala.collection.JavaConversions._
        val font1 = font0.deriveFont(Map(TextAttribute.SUPERSCRIPT -> new java.lang.Integer(i)))

        def shift(y: Float): Font =
          transform_font(font1, AffineTransform.getTranslateInstance(0.0, y.toDouble))

        val m0 = font_metrics(font0)
        val m1 = font_metrics(font1)
        val a = m1.getAscent - m0.getAscent
        val b = (m1.getDescent + m1.getLeading) - (m0.getDescent + m0.getLeading)
        if (a > 0.0f) shift(a)
        else if (b > 0.0f) shift(- b)
        else font1
      })
  }

  private def bold_style(style: SyntaxStyle): SyntaxStyle =
    font_style(style, _.deriveFont(Font.BOLD))

  val hidden_color: Color = new Color(255, 255, 255, 0)

  class Style_Extender extends SyntaxUtilities.StyleExtender
  {
    val max_user_fonts = 2
    if (Symbol.font_names.length > max_user_fonts)
      error("Too many user symbol fonts (max " + max_user_fonts + " permitted): " +
        Symbol.font_names.mkString(", "))

    override def extendStyles(styles: Array[SyntaxStyle]): Array[SyntaxStyle] =
    {
      val new_styles = new Array[SyntaxStyle](full_range)
      for (i <- 0 until plain_range) {
        val style = styles(i)
        new_styles(i) = style
        new_styles(subscript(i.toByte)) = script_style(style, -1)
        new_styles(superscript(i.toByte)) = script_style(style, 1)
        new_styles(bold(i.toByte)) = bold_style(style)
        for (idx <- 0 until max_user_fonts)
          new_styles(user_font(idx, i.toByte)) = style
        for ((family, idx) <- Symbol.font_index)
          new_styles(user_font(idx, i.toByte)) = font_style(style, imitate_font(family, _))
      }
      new_styles(hidden) =
        new SyntaxStyle(hidden_color, null,
          { val font = styles(0).getFont
            transform_font(new Font(font.getFamily, 0, 1),
              AffineTransform.getScaleInstance(1.0, font.getSize.toDouble)) })
      new_styles
    }
  }

  def extended_styles(text: CharSequence): Map[Text.Offset, Byte => Byte] =
  {
    // FIXME Symbol.bsub_decoded etc.
    def control_style(sym: String): Option[Byte => Byte] =
      if (sym == Symbol.sub_decoded || sym == Symbol.isub_decoded) Some(subscript(_))
      else if (sym == Symbol.sup_decoded || sym == Symbol.isup_decoded) Some(superscript(_))
      else if (sym == Symbol.bold_decoded) Some(bold(_))
      else None

    var result = Map[Text.Offset, Byte => Byte]()
    def mark(start: Text.Offset, stop: Text.Offset, style: Byte => Byte)
    {
      for (i <- start until stop) result += (i -> style)
    }
    var offset = 0
    var control = ""
    for (sym <- Symbol.iterator(text)) {
      if (control_style(sym).isDefined) control = sym
      else if (control != "") {
        if (Symbol.is_controllable(sym) && sym != "\"" && !Symbol.fonts.isDefinedAt(sym)) {
          mark(offset - control.length, offset, _ => hidden)
          mark(offset, offset + sym.length, control_style(control).get)
        }
        control = ""
      }
      Symbol.lookup_font(sym) match {
        case Some(idx) => mark(offset, offset + sym.length, user_font(idx, _))
        case _ =>
      }
      offset += sym.length
    }
    result
  }


  /* token marker */

  private val isabelle_rules = new ParserRuleSet("isabelle", "MAIN")

  private class Line_Context(val context: Option[Scan.Context])
    extends TokenMarker.LineContext(isabelle_rules, null)
  {
    override def hashCode: Int = context.hashCode
    override def equals(that: Any): Boolean =
      that match {
        case other: Line_Context => context == other.context
        case _ => false
      }
  }

  class Marker(ext_styles: Boolean, get_syntax: => Option[Outer_Syntax]) extends TokenMarker
  {
    override def markTokens(context: TokenMarker.LineContext,
        handler: TokenHandler, raw_line: Segment): TokenMarker.LineContext =
    {
      val line_ctxt =
        context match {
          case c: Line_Context => c.context
          case _ => Some(Scan.Finished)
        }
      val line = if (raw_line == null) new Segment else raw_line

      val context1 =
      {
        val syntax = get_syntax
        val (styled_tokens, context1) =
          if (line_ctxt.isDefined && syntax.isDefined) {
            val (tokens, ctxt1) = syntax.get.scan_context(line, line_ctxt.get)
            val styled_tokens = tokens.map(tok => (Rendering.token_markup(syntax.get, tok), tok))
            (styled_tokens, new Line_Context(Some(ctxt1)))
          }
          else {
            val token = Token(Token.Kind.UNPARSED, line.subSequence(0, line.count).toString)
            (List((JEditToken.NULL, token)), new Line_Context(None))
          }

        val extended =
          if (ext_styles) extended_styles(line)
          else Map.empty[Text.Offset, Byte => Byte]

        var offset = 0
        for ((style, token) <- styled_tokens) {
          val length = token.source.length
          val end_offset = offset + length
          if ((offset until end_offset) exists
              (i => extended.isDefinedAt(i) || line.charAt(i) == '\t')) {
            for (i <- offset until end_offset) {
              val style1 =
                extended.get(i) match {
                  case None => style
                  case Some(ext) => ext(style)
                }
              handler.handleToken(line, style1, i, 1, context1)
            }
          }
          else handler.handleToken(line, style, offset, length, context1)
          offset += length
        }
        handler.handleToken(line, JEditToken.END, line.count, 0, context1)
        context1
      }
      val context2 = context1.intern
      handler.setLineContext(context2)
      context2
    }
  }


  /* mode provider */

  private val markers = Map(
    "isabelle" -> new Token_Markup.Marker(true, PIDE.get_recent_syntax()),
    "isabelle-options" -> new Token_Markup.Marker(false, Some(Options.options_syntax)),
    "isabelle-root" -> new Token_Markup.Marker(false, Some(Build.root_syntax)))

  class Mode_Provider(orig_provider: ModeProvider) extends ModeProvider
  {
    for (mode <- orig_provider.getModes) addMode(mode)

    override def loadMode(mode: Mode, xmh: XModeHandler)
    {
      super.loadMode(mode, xmh)
      markers.get(mode.getName).map(mode.setTokenMarker(_))
    }
  }

  def refresh_buffer(buffer: JEditBuffer)
  {
    buffer.setTokenMarker(jEdit.getMode("text").getTokenMarker)
    markers.get(buffer.getMode.getName).map(buffer.setTokenMarker(_))
  }
}

