/*  Title:      Tools/jEdit/src/jedit_lib.scala
    Author:     Makarius

Misc library functions for jEdit.
*/

package isabelle.jedit


import isabelle._

import java.awt.{Component, Container, GraphicsEnvironment, Point, Rectangle, Dimension}
import java.awt.event.{InputEvent, KeyEvent, KeyListener}
import javax.swing.{Icon, ImageIcon, JWindow, SwingUtilities}

import scala.annotation.tailrec

import org.gjt.sp.jedit.{jEdit, Buffer, View, GUIUtilities, Debug}
import org.gjt.sp.jedit.gui.KeyEventWorkaround
import org.gjt.sp.jedit.buffer.JEditBuffer
import org.gjt.sp.jedit.textarea.{JEditTextArea, TextArea, TextAreaPainter}


object JEdit_Lib
{
  /* location within multi-screen environment */

  final case class Screen_Location(point: Point, bounds: Rectangle)
  {
    def relative(parent: Component, size: Dimension): Point =
    {
      val w = size.width
      val h = size.height

      val x0 = parent.getLocationOnScreen.x
      val y0 = parent.getLocationOnScreen.y
      val x1 = x0 + parent.getWidth - w
      val y1 = y0 + parent.getHeight - h
      val x2 = point.x min (bounds.x + bounds.width - w)
      val y2 = point.y min (bounds.y + bounds.height - h)

      val location = new Point((x2 min x1) max x0, (y2 min y1) max y0)
      SwingUtilities.convertPointFromScreen(location, parent)
      location
    }
  }

  def screen_location(component: Component, point: Point): Screen_Location =
  {
    val screen_point = new Point(point.x, point.y)
    SwingUtilities.convertPointToScreen(screen_point, component)

    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment
    val screen_bounds =
      (for {
        device <- ge.getScreenDevices.iterator
        config <- device.getConfigurations.iterator
        bounds = config.getBounds
      } yield bounds).find(_.contains(screen_point)) getOrElse ge.getMaximumWindowBounds

    Screen_Location(screen_point, screen_bounds)
  }


  /* window geometry measurement */

  private lazy val dummy_window = new JWindow

  final case class Window_Geometry(width: Int, height: Int, inner_width: Int, inner_height: Int)
  {
    def deco_width: Int = width - inner_width
    def deco_height: Int = height - inner_height
  }

  def window_geometry(outer: Container, inner: Component): Window_Geometry =
  {
    Swing_Thread.require()

    val old_content = dummy_window.getContentPane

    dummy_window.setContentPane(outer)
    dummy_window.pack
    dummy_window.revalidate

    val geometry =
      Window_Geometry(
        dummy_window.getWidth, dummy_window.getHeight, inner.getWidth, inner.getHeight)

    dummy_window.setContentPane(old_content)

    geometry
  }


  /* buffers */

  def swing_buffer_lock[A](buffer: JEditBuffer)(body: => A): A =
    Swing_Thread.now { buffer_lock(buffer) { body } }

  def buffer_text(buffer: JEditBuffer): String =
    buffer_lock(buffer) { buffer.getText(0, buffer.getLength) }

  def buffer_mode(buffer: JEditBuffer): String =
  {
    val mode = buffer.getMode
    if (mode == null) ""
    else {
      val name = mode.getName
      if (name == null) "" else name
    }
  }

  def buffer_name(buffer: Buffer): String = buffer.getSymlinkPath


  /* main jEdit components */

  def jedit_buffers(): Iterator[Buffer] = jEdit.getBuffers().iterator

  def jedit_buffer(name: String): Option[Buffer] =
    jedit_buffers().find(buffer => buffer_name(buffer) == name)

  def jedit_buffer(name: Document.Node.Name): Option[Buffer] =
    jedit_buffer(name.node)

  def jedit_views(): Iterator[View] = jEdit.getViews().iterator

  def jedit_text_areas(view: View): Iterator[JEditTextArea] =
    if (view == null) Iterator.empty
    else view.getEditPanes().iterator.filter(_ != null).map(_.getTextArea).filter(_ != null)

  def jedit_text_areas(): Iterator[JEditTextArea] =
    jedit_views().flatMap(jedit_text_areas(_))

  def jedit_text_areas(buffer: JEditBuffer): Iterator[JEditTextArea] =
    jedit_text_areas().filter(_.getBuffer == buffer)

  def buffer_lock[A](buffer: JEditBuffer)(body: => A): A =
  {
    try { buffer.readLock(); body }
    finally { buffer.readUnlock() }
  }

  def buffer_edit[A](buffer: JEditBuffer)(body: => A): A =
  {
    try { buffer.beginCompoundEdit(); body }
    finally { buffer.endCompoundEdit() }
  }


  /* get text */

  def try_get_text(buffer: JEditBuffer, range: Text.Range): Option[String] =
    try { Some(buffer.getText(range.start, range.length)) }
    catch { case _: ArrayIndexOutOfBoundsException => None }

  def try_get_text(text: String, range: Text.Range): Option[String] =
    try { Some(text.substring(range.start, range.stop)) }
    catch { case _: IndexOutOfBoundsException => None }


  /* point range */

  def point_range(buffer: JEditBuffer, offset: Text.Offset): Text.Range =
    if (offset < 0) Text.Range.offside
    else
      buffer_lock(buffer) {
        def text(i: Text.Offset): Char = buffer.getText(i, 1).charAt(0)
        try {
          val c = text(offset)
          if (Character.isHighSurrogate(c) && Character.isLowSurrogate(text(offset + 1)))
            Text.Range(offset, offset + 2)
          else if (Character.isLowSurrogate(c) && Character.isHighSurrogate(text(offset - 1)))
            Text.Range(offset - 1, offset + 1)
          else
            Text.Range(offset, offset + 1)
        }
        catch { case _: ArrayIndexOutOfBoundsException => Text.Range(offset, offset + 1) }
      }


  /* text ranges */

  def buffer_range(buffer: JEditBuffer): Text.Range =
    Text.Range(0, buffer.getLength)

  def line_range(buffer: JEditBuffer, line: Int): Text.Range =
    Text.Range(buffer.getLineStartOffset(line), buffer.getLineEndOffset(line) min buffer.getLength)

  def caret_range(text_area: TextArea): Text.Range =
    point_range(text_area.getBuffer, text_area.getCaretPosition)

  def before_caret_range(text_area: TextArea, rendering: Rendering): Text.Range =
  {
    val snapshot = rendering.snapshot
    val former_caret = snapshot.revert(text_area.getCaretPosition)
    snapshot.convert(Text.Range(former_caret - 1, former_caret))
  }

  def visible_range(text_area: TextArea): Option[Text.Range] =
  {
    val buffer = text_area.getBuffer
    val n = text_area.getVisibleLines
    if (n > 0) {
      val start = text_area.getScreenLineStartOffset(0)
      val raw_end = text_area.getScreenLineEndOffset(n - 1)
      val end = if (raw_end >= 0) raw_end min buffer.getLength else buffer.getLength
      Some(Text.Range(start, end))
    }
    else None
  }

  def invalidate_range(text_area: TextArea, range: Text.Range)
  {
    val buffer = text_area.getBuffer
    buffer_range(buffer).try_restrict(range) match {
      case Some(range1) if !range1.is_singularity =>
        text_area.invalidateLineRange(
          buffer.getLineOfOffset(range1.start),
          buffer.getLineOfOffset(range1.stop))
      case _ =>
    }
  }


  /* graphics range */

  case class Gfx_Range(val x: Int, val y: Int, val length: Int)

  // NB: jEdit always normalizes \r\n and \r to \n
  // NB: last line lacks \n
  def gfx_range(text_area: TextArea, range: Text.Range): Option[Gfx_Range] =
  {
    val metric = pretty_metric(text_area.getPainter)
    val char_width = (metric.unit * metric.average).round.toInt

    val buffer = text_area.getBuffer

    val end = buffer.getLength
    val stop = range.stop

    val (p, q, r) =
      try {
        val p = text_area.offsetToXY(range.start)
        val (q, r) =
          if (try_get_text(buffer, Text.Range(stop - 1, stop)) == Some("\n"))
            (text_area.offsetToXY(stop - 1), char_width)
          else if (stop >= end)
            (text_area.offsetToXY(end), char_width * (stop - end))
          else (text_area.offsetToXY(stop), 0)
        (p, q, r)
      }
      catch { case _: ArrayIndexOutOfBoundsException => (null, null, 0) }

    if (p != null && q != null && p.x < q.x + r && p.y == q.y)
      Some(Gfx_Range(p.x, p.y, q.x + r - p.x))
    else None
  }


  /* pixel range */

  def pixel_range(text_area: TextArea, x: Int, y: Int): Option[Text.Range] =
  {
    // coordinates wrt. inner painter component
    val painter = text_area.getPainter
    if (0 <= x && x < painter.getWidth && 0 <= y && y < painter.getHeight) {
      val offset = text_area.xyToOffset(x, y, false)
      if (offset >= 0) {
        val range = point_range(text_area.getBuffer, offset)
        gfx_range(text_area, range) match {
          case Some(g) if (g.x <= x && x < g.x + g.length) => Some(range)
          case _ => None
        }
      }
      else None
    }
    else None
  }


  /* pretty text metric */

  abstract class Pretty_Metric extends Pretty.Metric
  {
    def average: Double
  }

  def pretty_metric(painter: TextAreaPainter): Pretty_Metric =
    new Pretty_Metric {
      def string_width(s: String): Double =
        painter.getFont.getStringBounds(s, painter.getFontRenderContext).getWidth

      val unit = string_width(Pretty.space)
      val average = string_width("mix") / (3 * unit)
      def apply(s: String): Double = if (s == "\n") 1.0 else string_width(s) / unit
    }


  /* icons */

  def load_icon(name: String): Icon =
  {
    val name1 =
      if (name.startsWith("idea-icons/")) {
        val file =
          Isabelle_System.platform_file_url(Path.explode("$JEDIT_HOME/dist/jars/idea-icons.jar"))
        "jar:" + file + "!/" + name
      }
      else name
    val icon = GUIUtilities.loadIcon(name1)
    if (icon.getIconWidth < 0 || icon.getIconHeight < 0) error("Bad icon: " + name)
    else icon
  }

  def load_image_icon(name: String): ImageIcon =
    load_icon(name) match {
      case icon: ImageIcon => icon
      case _ => error("Bad image icon: " + name)
    }


  /* key event handling */

  def request_focus_view
  {
    val view = jEdit.getActiveView()
    if (view != null) {
      val text_area = view.getTextArea
      if (text_area != null) text_area.requestFocus
    }
  }

  def propagate_key(view: View, evt: KeyEvent)
  {
    if (view != null && !evt.isConsumed)
      view.getInputHandler().processKeyEvent(evt, View.ACTION_BAR, false)
  }

  def key_listener(
    key_typed: KeyEvent => Unit = _ => (),
    key_pressed: KeyEvent => Unit = _ => (),
    key_released: KeyEvent => Unit = _ => ()): KeyListener =
  {
    def process_key_event(evt0: KeyEvent, handle: KeyEvent => Unit)
    {
      val evt = KeyEventWorkaround.processKeyEvent(evt0)
      if (evt != null) handle(evt)
    }

    new KeyListener
    {
      def keyTyped(evt: KeyEvent) { process_key_event(evt, key_typed) }
      def keyPressed(evt: KeyEvent) { process_key_event(evt, key_pressed) }
      def keyReleased(evt: KeyEvent) { process_key_event(evt, key_released) }
    }
  }

  def special_key(evt: KeyEvent): Boolean =
  {
    // cf. 5.1.0/jEdit/org/gjt/sp/jedit/gui/KeyEventWorkaround.java
    val mod = evt.getModifiers
    (mod & InputEvent.CTRL_MASK) != 0 && (mod & InputEvent.ALT_MASK) == 0 ||
    (mod & InputEvent.CTRL_MASK) == 0 && (mod & InputEvent.ALT_MASK) != 0 &&
      !Debug.ALT_KEY_PRESSED_DISABLED ||
    (mod & InputEvent.META_MASK) != 0
  }
}

