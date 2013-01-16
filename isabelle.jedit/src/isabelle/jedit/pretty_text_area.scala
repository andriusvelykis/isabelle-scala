/*  Title:      Tools/jEdit/src/pretty_text_area.scala
    Author:     Makarius

GUI component for pretty-printed text with markup, rendered like jEdit
text area.
*/

package isabelle.jedit


import isabelle._

import java.awt.{Color, Font, FontMetrics, Toolkit, Window}
import java.awt.event.{KeyEvent, KeyAdapter}

import org.gjt.sp.jedit.{jEdit, View, Registers}
import org.gjt.sp.jedit.textarea.{AntiAlias, JEditEmbeddedTextArea}
import org.gjt.sp.jedit.syntax.SyntaxStyle
import org.gjt.sp.util.{SyntaxUtilities, Log}


object Pretty_Text_Area
{
  private def text_rendering(base_snapshot: Document.Snapshot, base_results: Command.Results,
    formatted_body: XML.Body): (String, Rendering) =
  {
    val (text, state) = Pretty_Text_Area.document_state(base_snapshot, base_results, formatted_body)
    val rendering = Rendering(state.snapshot(), PIDE.options.value)
    (text, rendering)
  }

  private def document_state(base_snapshot: Document.Snapshot, base_results: Command.Results,
    formatted_body: XML.Body): (String, Document.State) =
  {
    val command = Command.rich_text(Document.new_id(), base_results, formatted_body)
    val node_name = command.node_name
    val edits: List[Document.Edit_Text] =
      List(node_name -> Document.Node.Edits(List(Text.Edit.insert(0, command.source))))

    val state0 = base_snapshot.state.define_command(command)
    val version0 = base_snapshot.version
    val nodes0 = version0.nodes

    val nodes1 = nodes0 + (node_name -> nodes0(node_name).update_commands(Linear_Set(command)))
    val version1 = Document.Version.make(version0.syntax, nodes1)
    val state1 =
      state0.continue_history(Future.value(version0), edits, Future.value(version1))._2
        .define_version(version1, state0.the_assignment(version0))
        .assign(version1.id, List(command.id -> Some(Document.new_id())))._2

    (command.source, state1)
  }
}

class Pretty_Text_Area(
  view: View,
  background: Option[Color] = None,
  close_action: () => Unit = () => (),
  propagate_keys: Boolean = false) extends JEditEmbeddedTextArea
{
  text_area =>

  Swing_Thread.require()

  private var current_font_family = "Dialog"
  private var current_font_size: Int = 12
  private var current_body: XML.Body = Nil
  private var current_base_snapshot = Document.State.init.snapshot()
  private var current_base_results = Command.Results.empty
  private var current_rendering: Rendering =
    Pretty_Text_Area.text_rendering(current_base_snapshot, current_base_results, Nil)._2
  private var future_rendering: Option[java.util.concurrent.Future[Unit]] = None

  private val rich_text_area =
    new Rich_Text_Area(view, text_area, () => current_rendering, close_action,
      caret_visible = false, hovering = true)

  def refresh()
  {
    Swing_Thread.require()

    val font = new Font(current_font_family, Font.PLAIN, current_font_size)
    getPainter.setFont(font)
    getPainter.setAntiAlias(new AntiAlias(jEdit.getProperty("view.antiAlias")))
    getPainter.setStyles(SyntaxUtilities.loadStyles(current_font_family, current_font_size))

    val fold_line_style = new Array[SyntaxStyle](4)
    for (i <- 0 to 3) {
      fold_line_style(i) =
        SyntaxUtilities.parseStyle(
          jEdit.getProperty("view.style.foldLine." + i),
          current_font_family, current_font_size, true)
    }
    getPainter.setFoldLineStyle(fold_line_style)

    if (getWidth > 0) {
      getGutter.setForeground(jEdit.getColorProperty("view.gutter.fgColor"))
      getGutter.setBackground(jEdit.getColorProperty("view.gutter.bgColor"))
      background.map(bg => { getPainter.setBackground(bg); getGutter.setBackground(bg) })
      getGutter.setHighlightedForeground(jEdit.getColorProperty("view.gutter.highlightColor"))
      getGutter.setFoldColor(jEdit.getColorProperty("view.gutter.foldColor"))
      getGutter.setFont(jEdit.getFontProperty("view.gutter.font"))
      getGutter.setBorder(0,
        jEdit.getColorProperty("view.gutter.focusBorderColor"),
        jEdit.getColorProperty("view.gutter.noFocusBorderColor"),
        getPainter.getBackground)
      getGutter.setFoldPainter(getFoldPainter)

      getGutter.setGutterEnabled(jEdit.getBooleanProperty("view.gutter.enabled"))

      val fm = getPainter.getFontMetrics
      val margin = ((getWidth - getGutter.getWidth) / (Pretty.char_width_int(fm) max 1) - 2) max 20

      val base_snapshot = current_base_snapshot
      val base_results = current_base_results
      val formatted_body = Pretty.formatted(current_body, margin, Pretty.font_metric(fm))

      future_rendering.map(_.cancel(true))
      future_rendering = Some(default_thread_pool.submit(() =>
        {
          val (text, rendering) =
            try { Pretty_Text_Area.text_rendering(base_snapshot, base_results, formatted_body) }
            catch { case exn: Throwable => Log.log(Log.ERROR, this, exn); throw exn }
          Simple_Thread.interrupted_exception()

          Swing_Thread.later {
            current_rendering = rendering
            JEdit_Lib.buffer_edit(getBuffer) {
              rich_text_area.active_reset()
              getBuffer.setReadOnly(false)
              getBuffer.setFoldHandler(new Fold_Handling.Document_Fold_Handler(rendering))
              setText(text)
              setCaretPosition(0)
              getBuffer.setReadOnly(true)
            }
          }
        }))
    }
  }

  def resize(font_family: String, font_size: Int)
  {
    Swing_Thread.require()

    current_font_family = font_family
    current_font_size = font_size
    refresh()
  }

  def update(base_snapshot: Document.Snapshot, base_results: Command.Results, body: XML.Body)
  {
    Swing_Thread.require()
    require(!base_snapshot.is_outdated)

    current_base_snapshot = base_snapshot
    current_base_results = base_results
    current_body = body
    refresh()
  }


  /* key handling */

  addKeyListener(new KeyAdapter {
    override def keyPressed(evt: KeyEvent)
    {
      evt.getKeyCode match {
        case KeyEvent.VK_C
        if (evt.getModifiers & Toolkit.getDefaultToolkit.getMenuShortcutKeyMask) != 0 =>
          Registers.copy(text_area, '$')
          evt.consume
        case KeyEvent.VK_ESCAPE =>
          Window.getWindows foreach {
            case c: Pretty_Tooltip => c.dispose
            case _ =>
          }
          evt.consume
        case _ =>
      }
      if (propagate_keys && !evt.isConsumed)
        view.getInputHandler.processKeyEvent(evt, View.ACTION_BAR, false)
    }

    override def keyTyped(evt: KeyEvent)
    {
      if (propagate_keys && !evt.isConsumed)
        view.getInputHandler.processKeyEvent(evt, View.ACTION_BAR, false)
    }
  })


  /* init */

  getPainter.setStructureHighlightEnabled(false)
  getPainter.setLineHighlightEnabled(false)

  getBuffer.setTokenMarker(new Token_Markup.Marker(true, None))
  getBuffer.setReadOnly(true)

  rich_text_area.activate()
}

