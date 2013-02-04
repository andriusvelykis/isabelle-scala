/*  Title:      Tools/jEdit/src/graphview_dockable.scala
    Author:     Makarius

Stateless dockable window for graphview.
*/

package isabelle.jedit


import isabelle._

import javax.swing.JComponent
import java.awt.event.{WindowFocusListener, WindowEvent}

import org.gjt.sp.jedit.View

import scala.swing.TextArea


object Graphview_Dockable
{
  /* implicit arguments -- owned by Swing thread */

  private var implicit_snapshot = Document.State.init.snapshot()

  private val no_graph: Exn.Result[graphview.Model.Graph] = Exn.Exn(ERROR("No graph"))
  private var implicit_graph = no_graph

  private def set_implicit(snapshot: Document.Snapshot, graph: Exn.Result[graphview.Model.Graph])
  {
    Swing_Thread.require()

    implicit_snapshot = snapshot
    implicit_graph = graph
  }

  private def reset_implicit(): Unit =
    set_implicit(Document.State.init.snapshot(), no_graph)

  def apply(view: View, snapshot: Document.Snapshot, graph: Exn.Result[graphview.Model.Graph])
  {
    set_implicit(snapshot, graph)
    view.getDockableWindowManager.floatDockableWindow("isabelle-graphview")
  }
}


class Graphview_Dockable(view: View, position: String) extends Dockable(view, position)
{
  Swing_Thread.require()

  private val snapshot = Graphview_Dockable.implicit_snapshot
  private val graph = Graphview_Dockable.implicit_graph

  private val window_focus_listener =
    new WindowFocusListener {
      def windowGainedFocus(e: WindowEvent) { Graphview_Dockable.set_implicit(snapshot, graph) }
      def windowLostFocus(e: WindowEvent) { Graphview_Dockable.reset_implicit() }
    }

  val graphview =
    graph match {
      case Exn.Res(proper_graph) =>
        new isabelle.graphview.Main_Panel(proper_graph) {
          override def make_tooltip(parent: JComponent, x: Int, y: Int, body: XML.Body): String =
          {
            val rendering = Rendering(snapshot, PIDE.options.value)
            new Pretty_Tooltip(view, parent, rendering, x, y, Command.Results.empty, body)
            null
          }
        }
      case Exn.Exn(exn) => new TextArea(Exn.message(exn))
    }
  set_content(graphview)

  override def init()
  {
    Swing_Thread.require()
    JEdit_Lib.parent_window(this).map(_.addWindowFocusListener(window_focus_listener))
  }

  override def exit()
  {
    Swing_Thread.require()
    JEdit_Lib.parent_window(this).map(_.removeWindowFocusListener(window_focus_listener))
  }
}
