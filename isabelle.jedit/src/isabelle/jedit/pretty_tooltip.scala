/*  Title:      Tools/jEdit/src/pretty_tooltip.scala
    Author:     Makarius

Enhanced tooltip window based on Pretty_Text_Area.
*/

package isabelle.jedit


import isabelle._

import java.awt.{Toolkit, Color, Point, BorderLayout, Window, Dimension}
import java.awt.event.{ActionListener, ActionEvent, KeyEvent, WindowEvent, WindowAdapter}
import javax.swing.{SwingUtilities, JWindow, JPanel, JComponent, KeyStroke}
import javax.swing.border.LineBorder

import scala.swing.{FlowPanel, Label}
import scala.swing.event.MouseClicked

import org.gjt.sp.jedit.View
import org.gjt.sp.jedit.textarea.TextArea


class Pretty_Tooltip(
  view: View,
  parent: JComponent,
  rendering: Rendering,
  mouse_x: Int, mouse_y: Int, body: XML.Body)
  extends JWindow(JEdit_Lib.parent_window(parent) getOrElse view)
{
  window =>

  Swing_Thread.require()


  window.addWindowFocusListener(new WindowAdapter {
    override def windowLostFocus(e: WindowEvent) {
      if (!Window.getWindows.exists(w =>
            w.isDisplayable && JEdit_Lib.ancestors(w).exists(_ == window)))
        window.dispose()
    }
  })

  private val action_listener = new ActionListener {
    def actionPerformed(e: ActionEvent) {
      e.getActionCommand match {
        case "close_all" =>
          Window.getWindows foreach {
            case c: Pretty_Tooltip => c.dispose
            case _ =>
          }
        case _ =>
      }
    }
  }

  window.setContentPane(new JPanel(new BorderLayout) {
    setBackground(rendering.tooltip_color)
    registerKeyboardAction(action_listener, "close_all",
      KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)

    override def getFocusTraversalKeysEnabled(): Boolean = false
  })
  window.getRootPane.setBorder(new LineBorder(Color.BLACK))


  /* pretty text area */

  val pretty_text_area = new Pretty_Text_Area(view)
  pretty_text_area.getPainter.setBackground(rendering.tooltip_color)
  pretty_text_area.resize(Isabelle.font_family(),
    Isabelle.font_size("jedit_tooltip_font_scale").round)
  pretty_text_area.update(rendering.snapshot, body)

  pretty_text_area.registerKeyboardAction(action_listener, "close_all",
    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)

  window.add(pretty_text_area)


  /* controls */

  private val close = new Label {
    icon = Rendering.tooltip_close_icon
    tooltip = "Close tooltip window"
    listenTo(mouse.clicks)
    reactions += { case _: MouseClicked => window.dispose() }
  }

  private val detach = new Label {
    icon = Rendering.tooltip_detach_icon
    tooltip = "Detach tooltip window"
    listenTo(mouse.clicks)
    reactions += {
      case _: MouseClicked => Info_Dockable(view, rendering.snapshot, body); window.dispose()
    }
  }

  private val controls = new FlowPanel(FlowPanel.Alignment.Left)(close, detach) {
    background = rendering.tooltip_color
  }
  window.add(controls.peer, BorderLayout.NORTH)


  /* window geometry */

  {
    val font_metrics = pretty_text_area.getPainter.getFontMetrics
    val margin = Isabelle.options.int("jedit_tooltip_margin")  // FIXME via rendering?!
    val lines =
      XML.traverse_text(Pretty.formatted(body, margin, Pretty.font_metric(font_metrics)))(0)(
        (n: Int, s: String) => n + s.iterator.filter(_ == '\n').length)

    val screen = Toolkit.getDefaultToolkit.getScreenSize
    val w = (font_metrics.charWidth(Pretty.spc) * (margin + 2)) min (screen.width / 2)
    val h = (font_metrics.getHeight * (lines + 2)) min (screen.height / 2)
    pretty_text_area.setPreferredSize(new Dimension(w, h))
    window.pack
  }

  {
    val point = new Point(mouse_x, mouse_y)
    SwingUtilities.convertPointToScreen(point, parent)

    val screen = Toolkit.getDefaultToolkit.getScreenSize
    val x = point.x min (screen.width - window.getWidth)
    val y = point.y min (screen.height - window.getHeight)
    window.setLocation(x, y)
  }

  window.setVisible(true)
  pretty_text_area.refresh()
}

