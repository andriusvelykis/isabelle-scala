/*  Title:      Tools/jEdit/src/rich_text_area.scala
    Author:     Makarius

Enhanced version of jEdit text area, with rich text rendering,
tooltips, hyperlinks etc.
*/

package isabelle.jedit


import isabelle._

import java.awt.{Graphics2D, Shape, Color, Point, Toolkit, Cursor, MouseInfo}
import java.awt.event.{MouseMotionAdapter, MouseAdapter, MouseEvent,
  FocusAdapter, FocusEvent, WindowEvent, WindowAdapter}
import java.awt.font.TextAttribute
import javax.swing.SwingUtilities
import java.text.AttributedString
import java.util.ArrayList

import org.gjt.sp.util.Log
import org.gjt.sp.jedit.{OperatingSystem, Debug, View}
import org.gjt.sp.jedit.syntax.{DisplayTokenHandler, Chunk}
import org.gjt.sp.jedit.textarea.{TextAreaExtension, TextAreaPainter, TextArea}


class Rich_Text_Area(
  view: View,
  text_area: TextArea,
  get_rendering: () => Rendering,
  close_action: () => Unit,
  caret_visible: Boolean,
  enable_hovering: Boolean)
{
  private val buffer = text_area.getBuffer


  /* robust extension body */

  def robust_body[A](default: A)(body: => A): A =
  {
    try {
      Swing_Thread.require()
      if (buffer == text_area.getBuffer) body
      else {
        Log.log(Log.ERROR, this, ERROR("Implicit change of text area buffer"))
        default
      }
    }
    catch { case exn: Throwable => Log.log(Log.ERROR, this, exn); default }
  }


  /* original painters */

  private def pick_extension(name: String): TextAreaExtension =
  {
    text_area.getPainter.getExtensions.iterator.filter(x => x.getClass.getName == name).toList
    match {
      case List(x) => x
      case _ => error("Expected exactly one " + name)
    }
  }

  private val orig_text_painter =
    pick_extension("org.gjt.sp.jedit.textarea.TextAreaPainter$PaintText")


  /* common painter state */

  @volatile private var painter_rendering: Rendering = null
  @volatile private var painter_clip: Shape = null

  private val set_state = new TextAreaExtension
  {
    override def paintScreenLineRange(gfx: Graphics2D,
      first_line: Int, last_line: Int, physical_lines: Array[Int],
      start: Array[Int], end: Array[Int], y: Int, line_height: Int)
    {
      painter_rendering = get_rendering()
      painter_clip = gfx.getClip
    }
  }

  private val reset_state = new TextAreaExtension
  {
    override def paintScreenLineRange(gfx: Graphics2D,
      first_line: Int, last_line: Int, physical_lines: Array[Int],
      start: Array[Int], end: Array[Int], y: Int, line_height: Int)
    {
      painter_rendering = null
      painter_clip = null
    }
  }

  def robust_rendering(body: Rendering => Unit)
  {
    robust_body(()) { body(painter_rendering) }
  }


  /* active areas within the text */

  private class Active_Area[A](
    rendering: Rendering => Text.Range => Option[Text.Info[A]],
    cursor: Option[Int])
  {
    private var the_text_info: Option[(String, Text.Info[A])] = None

    def is_active: Boolean = the_text_info.isDefined
    def text_info: Option[(String, Text.Info[A])] = the_text_info
    def info: Option[Text.Info[A]] = the_text_info.map(_._2)

    def update(new_info: Option[Text.Info[A]])
    {
      val old_text_info = the_text_info
      val new_text_info =
        new_info.map(info => (text_area.getText(info.range.start, info.range.length), info))

      if (new_text_info != old_text_info) {
        if (cursor.isDefined) {
          if (new_text_info.isDefined)
            text_area.getPainter.setCursor(Cursor.getPredefinedCursor(cursor.get))
          else
            text_area.getPainter.resetCursor()
        }
        for {
          r0 <- JEdit_Lib.visible_range(text_area)
          opt <- List(old_text_info, new_text_info)
          (_, Text.Info(r1, _)) <- opt
          r2 <- r1.try_restrict(r0)  // FIXME more precise?!
        } JEdit_Lib.invalidate_range(text_area, r2)
        the_text_info = new_text_info
      }
    }

    def update_rendering(r: Rendering, range: Text.Range)
    { update(rendering(r)(range)) }

    def reset { update(None) }
  }

  // owned by Swing thread

  private val highlight_area =
    new Active_Area[Color]((r: Rendering) => r.highlight _, None)
  private val hyperlink_area =
    new Active_Area[PIDE.editor.Hyperlink](
      (r: Rendering) => r.hyperlink _, Some(Cursor.HAND_CURSOR))
  private val active_area =
    new Active_Area[XML.Elem]((r: Rendering) => r.active _, Some(Cursor.DEFAULT_CURSOR))

  private val active_areas =
    List((highlight_area, true), (hyperlink_area, true), (active_area, false))
  def active_reset(): Unit = active_areas.foreach(_._1.reset)

  private val focus_listener = new FocusAdapter {
    override def focusLost(e: FocusEvent) { robust_body(()) { active_reset() } }
  }

  private val window_listener = new WindowAdapter {
    override def windowIconified(e: WindowEvent) { robust_body(()) { active_reset() } }
    override def windowDeactivated(e: WindowEvent) { robust_body(()) { active_reset() } }
  }

  private val mouse_listener = new MouseAdapter {
    override def mouseClicked(e: MouseEvent) {
      robust_body(()) {
        hyperlink_area.info match {
          case Some(Text.Info(range, link)) =>
            try { text_area.moveCaretPosition(range.start) }
            catch {
              case _: ArrayIndexOutOfBoundsException =>
              case _: IllegalArgumentException =>
            }
            text_area.requestFocus
            link.follow(view)
          case None =>
        }
        active_area.text_info match {
          case Some((text, Text.Info(_, markup))) =>
            Active.action(view, text, markup)
            close_action()
          case None =>
        }
      }
    }
  }

  private def mouse_inside_painter(): Boolean =
    MouseInfo.getPointerInfo match {
      case null => false
      case info =>
        val point = info.getLocation
        val painter = text_area.getPainter
        SwingUtilities.convertPointFromScreen(point, painter)
        painter.contains(point)
    }

  private val mouse_motion_listener = new MouseMotionAdapter {
    override def mouseDragged(evt: MouseEvent) {
      robust_body(()) {
        PIDE.dismissed_popups(view)
      }
    }

    override def mouseMoved(evt: MouseEvent) {
      robust_body(()) {
        val x = evt.getX
        val y = evt.getY
        val control = (evt.getModifiers & Toolkit.getDefaultToolkit.getMenuShortcutKeyMask) != 0

        if ((control || enable_hovering) && !buffer.isLoading) {
          JEdit_Lib.buffer_lock(buffer) {
            JEdit_Lib.pixel_range(text_area, x, y) match {
              case None => active_reset()
              case Some(range) =>
                val rendering = get_rendering()
                for ((area, require_control) <- active_areas)
                {
                  if (control == require_control && !rendering.snapshot.is_outdated)
                    area.update_rendering(rendering, range)
                  else area.reset
                }
            }
          }
        }
        else active_reset()

        if (evt.getSource == text_area.getPainter) {
          Pretty_Tooltip.invoke(() =>
            robust_body(()) {
              if (mouse_inside_painter()) {
                val rendering = get_rendering()
                val snapshot = rendering.snapshot
                if (!snapshot.is_outdated) {
                  JEdit_Lib.pixel_range(text_area, x, y) match {
                    case None =>
                    case Some(range) =>
                      val result =
                        if (control) rendering.tooltip(range)
                        else rendering.tooltip_message(range)
                      result match {
                        case None =>
                        case Some(tip) =>
                          val painter = text_area.getPainter
                          val loc = new Point(x, y + painter.getFontMetrics.getHeight / 2)
                          val results = rendering.command_results(range)
                          Pretty_Tooltip(view, painter, loc, rendering, results, tip)
                      }
                  }
                }
              }
          })
        }
      }
    }
  }


  /* text background */

  private val background_painter = new TextAreaExtension
  {
    override def paintScreenLineRange(gfx: Graphics2D,
      first_line: Int, last_line: Int, physical_lines: Array[Int],
      start: Array[Int], end: Array[Int], y: Int, line_height: Int)
    {
      robust_rendering { rendering =>
        val fm = text_area.getPainter.getFontMetrics

        for (i <- 0 until physical_lines.length) {
          if (physical_lines(i) != -1) {
            val line_range = Text.Range(start(i), end(i))

            // line background color
            for { (color, separator) <- rendering.line_background(line_range) }
            {
              gfx.setColor(color)
              val sep = if (separator) (2 min (line_height / 2)) else 0
              gfx.fillRect(0, y + i * line_height, text_area.getWidth, line_height - sep)
            }

            // background color
            for {
              Text.Info(range, color) <- rendering.background(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(color)
              gfx.fillRect(r.x, y + i * line_height, r.length, line_height)
            }

            // active area -- potentially from other snapshot
            for {
              info <- active_area.info
              Text.Info(range, _) <- info.try_restrict(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(rendering.active_hover_color)
              gfx.fillRect(r.x, y + i * line_height, r.length, line_height)
            }

            // squiggly underline
            for {
              Text.Info(range, color) <- rendering.squiggly_underline(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(color)
              val x0 = (r.x / 2) * 2
              val y0 = r.y + fm.getAscent + 1
              for (x1 <- Range(x0, x0 + r.length, 2)) {
                val y1 = if (x1 % 4 < 2) y0 else y0 + 1
                gfx.drawLine(x1, y1, x1 + 1, y1)
              }
            }
          }
        }
      }
    }
  }


  /* text */

  private def caret_enabled: Boolean =
    caret_visible && (!text_area.hasFocus || text_area.isCaretVisible)

  private def caret_color(rendering: Rendering): Color =
  {
    if (text_area.isCaretVisible)
      text_area.getPainter.getCaretColor
    else rendering.caret_invisible_color
  }

  private def paint_chunk_list(rendering: Rendering,
    gfx: Graphics2D, line_start: Text.Offset, head: Chunk, x: Float, y: Float): Float =
  {
    val clip_rect = gfx.getClipBounds
    val painter = text_area.getPainter
    val font_context = painter.getFontRenderContext

    val caret_range =
      if (caret_enabled) JEdit_Lib.point_range(buffer, text_area.getCaretPosition)
      else Text.Range.offside

    var w = 0.0f
    var chunk = head
    while (chunk != null) {
      val chunk_offset = line_start + chunk.offset
      if (x + w + chunk.width > clip_rect.x &&
          x + w < clip_rect.x + clip_rect.width && chunk.length > 0)
      {
        val chunk_range = Text.Range(chunk_offset, chunk_offset + chunk.length)
        val chunk_str = if (chunk.str == null) " " * chunk.length else chunk.str
        val chunk_font = chunk.style.getFont
        val chunk_color = chunk.style.getForegroundColor

        def string_width(s: String): Float =
          if (s.isEmpty) 0.0f
          else chunk_font.getStringBounds(s, font_context).getWidth.toFloat

        val markup =
          for {
            r1 <- rendering.text_color(chunk_range, chunk_color)
            r2 <- r1.try_restrict(chunk_range)
          } yield r2

        val padded_markup_iterator =
          if (markup.isEmpty)
            Iterator(Text.Info(chunk_range, chunk_color))
          else
            Iterator(
              Text.Info(Text.Range(chunk_range.start, markup.head.range.start), chunk_color)) ++
            markup.iterator ++
            Iterator(Text.Info(Text.Range(markup.last.range.stop, chunk_range.stop), chunk_color))

        var x1 = x + w
        gfx.setFont(chunk_font)
        for (Text.Info(range, color) <- padded_markup_iterator if !range.is_singularity) {
          val str = chunk_str.substring(range.start - chunk_offset, range.stop - chunk_offset)
          gfx.setColor(color)

          range.try_restrict(caret_range) match {
            case Some(r) if !r.is_singularity =>
              val i = r.start - range.start
              val j = r.stop - range.start
              val s1 = str.substring(0, i)
              val s2 = str.substring(i, j)
              val s3 = str.substring(j)

              if (!s1.isEmpty) gfx.drawString(s1, x1, y)

              val astr = new AttributedString(s2)
              astr.addAttribute(TextAttribute.FONT, chunk_font)
              astr.addAttribute(TextAttribute.FOREGROUND, caret_color(rendering))
              astr.addAttribute(TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON)
              gfx.drawString(astr.getIterator, x1 + string_width(s1), y)

              if (!s3.isEmpty)
                gfx.drawString(s3, x1 + string_width(str.substring(0, j)), y)

            case _ =>
              gfx.drawString(str, x1, y)
          }
          x1 += string_width(str)
        }
      }
      w += chunk.width
      chunk = chunk.next.asInstanceOf[Chunk]
    }
    w
  }

  private val text_painter = new TextAreaExtension
  {
    override def paintScreenLineRange(gfx: Graphics2D,
      first_line: Int, last_line: Int, physical_lines: Array[Int],
      start: Array[Int], end: Array[Int], y: Int, line_height: Int)
    {
      robust_rendering { rendering =>
        val painter = text_area.getPainter
        val fm = painter.getFontMetrics
        val lm = painter.getFont.getLineMetrics(" ", painter.getFontRenderContext)

        val clip = gfx.getClip
        val x0 = text_area.getHorizontalOffset
        var y0 = y + fm.getHeight - (fm.getLeading + 1) - fm.getDescent

        val (bullet_x, bullet_y, bullet_w, bullet_h) =
        {
          val w = fm.charWidth(' ')
          val b = (w / 2) max 1
          val c = (lm.getAscent + lm.getStrikethroughOffset).round.toInt
          ((w - b + 1) / 2, c - b / 2, w - b, line_height - b)
        }

        for (i <- 0 until physical_lines.length) {
          val line = physical_lines(i)
          if (line != -1) {
            val line_range = Text.Range(start(i), end(i))

            // bullet bar
            for {
              Text.Info(range, color) <- rendering.bullet(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(color)
              gfx.fillRect(r.x + bullet_x, y + i * line_height + bullet_y,
                r.length - bullet_w, line_height - bullet_h)
            }

            // text chunks
            val screen_line = first_line + i
            val chunks = text_area.getChunksOfScreenLine(screen_line)
            if (chunks != null) {
              try {
                val line_start = buffer.getLineStartOffset(line)
                gfx.clipRect(x0, y + line_height * i, Integer.MAX_VALUE, line_height)
                val w = paint_chunk_list(rendering, gfx, line_start, chunks, x0, y0).toInt
                gfx.clipRect(x0 + w.toInt, 0, Integer.MAX_VALUE, Integer.MAX_VALUE)
                orig_text_painter.paintValidLine(gfx,
                  screen_line, line, start(i), end(i), y + line_height * i)
              } finally { gfx.setClip(clip) }
            }
          }
          y0 += line_height
        }
      }
    }
  }


  /* foreground */

  private val foreground_painter = new TextAreaExtension
  {
    override def paintScreenLineRange(gfx: Graphics2D,
      first_line: Int, last_line: Int, physical_lines: Array[Int],
      start: Array[Int], end: Array[Int], y: Int, line_height: Int)
    {
      robust_rendering { rendering =>
        for (i <- 0 until physical_lines.length) {
          if (physical_lines(i) != -1) {
            val line_range = Text.Range(start(i), end(i))

            // foreground color
            for {
              Text.Info(range, color) <- rendering.foreground(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(color)
              gfx.fillRect(r.x, y + i * line_height, r.length, line_height)
            }

            // highlight range -- potentially from other snapshot
            for {
              info <- highlight_area.info
              Text.Info(range, color) <- info.try_restrict(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(color)
              gfx.fillRect(r.x, y + i * line_height, r.length, line_height)
            }

            // hyperlink range -- potentially from other snapshot
            for {
              info <- hyperlink_area.info
              Text.Info(range, _) <- info.try_restrict(line_range)
              r <- JEdit_Lib.gfx_range(text_area, range)
            } {
              gfx.setColor(rendering.hyperlink_color)
              gfx.drawRect(r.x, y + i * line_height, r.length - 1, line_height - 1)
            }

            // completion range
            if (!hyperlink_area.is_active && caret_visible) {
              for {
                completion <- Completion_Popup.Text_Area(text_area)
                Text.Info(range, color) <- completion.rendering(rendering, line_range)
                r <- JEdit_Lib.gfx_range(text_area, range)
              } {
                gfx.setColor(color)
                gfx.drawRect(r.x, y + i * line_height, r.length - 1, line_height - 1)
              }
            }
          }
        }
      }
    }
  }


  /* caret -- outside of text range */

  private class Caret_Painter(before: Boolean) extends TextAreaExtension
  {
    override def paintValidLine(gfx: Graphics2D,
      screen_line: Int, physical_line: Int, start: Int, end: Int, y: Int)
    {
      robust_rendering { _ =>
        if (before) gfx.clipRect(0, 0, 0, 0)
        else gfx.setClip(painter_clip)
      }
    }
  }

  private val before_caret_painter1 = new Caret_Painter(true)
  private val after_caret_painter1 = new Caret_Painter(false)
  private val before_caret_painter2 = new Caret_Painter(true)
  private val after_caret_painter2 = new Caret_Painter(false)

  private val caret_painter = new TextAreaExtension
  {
    override def paintValidLine(gfx: Graphics2D,
      screen_line: Int, physical_line: Int, start: Int, end: Int, y: Int)
    {
      robust_rendering { rendering =>
        if (caret_visible) {
          val caret = text_area.getCaretPosition
          if (caret_enabled && start <= caret && caret == end - 1) {
            val painter = text_area.getPainter
            val fm = painter.getFontMetrics

            val offset = caret - text_area.getLineStartOffset(physical_line)
            val x = text_area.offsetToXY(physical_line, offset).x
            val y1 = y + fm.getHeight - (fm.getLeading + 1) - fm.getDescent

            val astr = new AttributedString(" ")
            astr.addAttribute(TextAttribute.FONT, painter.getFont)
            astr.addAttribute(TextAttribute.FOREGROUND, caret_color(rendering))
            astr.addAttribute(TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON)

            val clip = gfx.getClip
            try {
              gfx.clipRect(x, y, Integer.MAX_VALUE, painter.getLineHeight)
              gfx.drawString(astr.getIterator, x, y1)
            }
            finally { gfx.setClip(clip) }
          }
        }
      }
    }
  }


  /* activation */

  def activate()
  {
    val painter = text_area.getPainter
    painter.addExtension(TextAreaPainter.LOWEST_LAYER, set_state)
    painter.addExtension(TextAreaPainter.LINE_BACKGROUND_LAYER + 1, background_painter)
    painter.addExtension(TextAreaPainter.TEXT_LAYER, text_painter)
    painter.addExtension(TextAreaPainter.CARET_LAYER - 1, before_caret_painter1)
    painter.addExtension(TextAreaPainter.CARET_LAYER + 1, after_caret_painter1)
    painter.addExtension(TextAreaPainter.BLOCK_CARET_LAYER - 1, before_caret_painter2)
    painter.addExtension(TextAreaPainter.BLOCK_CARET_LAYER + 1, after_caret_painter2)
    painter.addExtension(TextAreaPainter.BLOCK_CARET_LAYER + 2, caret_painter)
    painter.addExtension(500, foreground_painter)
    painter.addExtension(TextAreaPainter.HIGHEST_LAYER, reset_state)
    painter.removeExtension(orig_text_painter)
    painter.addMouseListener(mouse_listener)
    painter.addMouseMotionListener(mouse_motion_listener)
    text_area.addFocusListener(focus_listener)
    view.addWindowListener(window_listener)
  }

  def deactivate()
  {
    active_reset()
    val painter = text_area.getPainter
    view.removeWindowListener(window_listener)
    text_area.removeFocusListener(focus_listener)
    painter.removeMouseMotionListener(mouse_motion_listener)
    painter.removeMouseListener(mouse_listener)
    painter.addExtension(TextAreaPainter.TEXT_LAYER, orig_text_painter)
    painter.removeExtension(reset_state)
    painter.removeExtension(foreground_painter)
    painter.removeExtension(caret_painter)
    painter.removeExtension(after_caret_painter2)
    painter.removeExtension(before_caret_painter2)
    painter.removeExtension(after_caret_painter1)
    painter.removeExtension(before_caret_painter1)
    painter.removeExtension(text_painter)
    painter.removeExtension(background_painter)
    painter.removeExtension(set_state)
  }
}

