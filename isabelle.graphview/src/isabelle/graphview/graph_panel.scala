/*  Title:      Tools/Graphview/src/graph_panel.scala
    Author:     Markus Kaiser, TU Muenchen

Graphview Java2D drawing panel.
*/

package isabelle.graphview

import isabelle._

import java.awt.{Dimension, Graphics2D, Point, Rectangle}
import java.awt.geom.{AffineTransform, Point2D}
import java.awt.image.BufferedImage
import javax.swing.{JScrollPane, JComponent}

import scala.swing.{Panel, ScrollPane}
import scala.swing.event._


class Graph_Panel(
    val visualizer: Visualizer,
    make_tooltip: (JComponent, Int, Int, XML.Body) => String)
  extends ScrollPane
{
  panel =>

  tooltip = ""

  override lazy val peer: JScrollPane = new JScrollPane with SuperMixin {
    override def getToolTipText(event: java.awt.event.MouseEvent): String =
      node(Transform.pane_to_graph_coordinates(event.getPoint)) match {
        case Some(name) =>
          visualizer.model.complete.get_node(name).content match {
            case Nil => null
            case content => make_tooltip(panel.peer, event.getX, event.getY, content)
          }
        case None => null
      }
  }

  peer.setWheelScrollingEnabled(false)
  focusable = true
  requestFocus()

  horizontalScrollBarPolicy = ScrollPane.BarPolicy.Always
  verticalScrollBarPolicy = ScrollPane.BarPolicy.Always

  def node(at: Point2D): Option[String] =
    visualizer.model.visible_nodes()
      .find(name => visualizer.Drawer.shape(visualizer.gfx, Some(name)).contains(at))

  def refresh()
  {
    if (paint_panel != null) {
      paint_panel.set_preferred_size()
      paint_panel.repaint()
    }
  }

  def fit_to_window() = {
    Transform.fit_to_window()
    refresh()
  }

  val zoom_box: Library.Zoom_Box =
    new Library.Zoom_Box(percent => rescale(0.01 * percent))

  def rescale(s: Double)
  {
    Transform.scale = s
    if (zoom_box != null) zoom_box.set_item((Transform.scale_discrete * 100).round.toInt)
    refresh()
  }

  def apply_layout() = visualizer.Coordinates.update_layout()

  private class Paint_Panel extends Panel {
    def set_preferred_size() {
        val (minX, minY, maxX, maxY) = visualizer.Coordinates.bounds()
        val s = Transform.scale_discrete
        val (px, py) = Transform.padding

        preferredSize = new Dimension((math.abs(maxX - minX + px) * s).toInt,
                                      (math.abs(maxY - minY + py) * s).toInt)

        revalidate()
      }

    override def paint(g: Graphics2D) {
      super.paintComponent(g)
      g.transform(Transform())

      visualizer.Drawer.paint_all_visible(g, true)
    }
  }
  private val paint_panel = new Paint_Panel
  contents = paint_panel

  listenTo(keys)
  listenTo(mouse.moves)
  listenTo(mouse.clicks)
  listenTo(mouse.wheel)
  reactions += Interaction.Mouse.react
  reactions += Interaction.Keyboard.react
  reactions += {
      case KeyTyped(_, _, _, _) => {repaint(); requestFocus()}
      case MousePressed(_, _, _, _, _) => {repaint(); requestFocus()}
      case MouseDragged(_, _, _) => {repaint(); requestFocus()}
      case MouseWheelMoved(_, _, _, _) => {repaint(); requestFocus()}
      case MouseClicked(_, _, _, _, _) => {repaint(); requestFocus()}
      case MouseMoved(_, _, _) => repaint()
    }

  visualizer.model.Colors.events += { case _ => repaint() }
  visualizer.model.Mutators.events += { case _ => repaint() }

  apply_layout()
  rescale(1.0)

  private object Transform
  {
    val padding = (100, 40)

    private var _scale: Double = 1.0
    def scale: Double = _scale
    def scale_=(s: Double)
    {
      _scale = (s min 10) max 0.1
    }
    def scale_discrete: Double =
      (_scale * visualizer.font_size).round.toDouble / visualizer.font_size

    def apply() = {
      val (minX, minY, _, _) = visualizer.Coordinates.bounds()

      val at = AffineTransform.getScaleInstance(scale_discrete, scale_discrete)
      at.translate(-minX + padding._1 / 2, -minY + padding._2 / 2)
      at
    }

    def fit_to_window() {
      if (visualizer.model.visible_nodes().isEmpty)
        rescale(1.0)
      else {
        val (minX, minY, maxX, maxY) = visualizer.Coordinates.bounds()

        val (dx, dy) = (maxX - minX + padding._1, maxY - minY + padding._2)
        val (sx, sy) = (1.0 * size.width / dx, 1.0 * size.height / dy)
        rescale(sx min sy)
      }
    }

    def pane_to_graph_coordinates(at: Point2D): Point2D = {
      val s = Transform.scale_discrete
      val p = Transform().inverseTransform(peer.getViewport.getViewPosition, null)

      p.setLocation(p.getX + at.getX / s, p.getY + at.getY / s)
      p
    }
  }

  object Interaction {
    object Keyboard {
      import scala.swing.event.Key._

      val react: PartialFunction[Event, Unit] = {
        case KeyTyped(_, c, m, _) => typed(c, m)
      }

      def typed(c: Char, m: Modifiers) =
        (c, m) match {
          case ('+', _) => rescale(Transform.scale * 5.0/4)
          case ('-', _) => rescale(Transform.scale * 4.0/5)
          case ('0', _) => Transform.fit_to_window()
          case ('1', _) => visualizer.Coordinates.update_layout()
          case ('2', _) => Transform.fit_to_window()
          case _ =>
        }
    }

    object Mouse {
      import scala.swing.event.Key.Modifier._
      type Modifiers = Int
      type Dummy = ((String, String), Int)

      private var draginfo: (Point, Iterable[String], Iterable[Dummy]) = null

      val react: PartialFunction[Event, Unit] = {
        case MousePressed(_, p, _, _, _) => pressed(p)
        case MouseDragged(_, to, _) => {
            drag(draginfo, to)
            val (_, p, d) = draginfo
            draginfo = (to, p, d)
          }
        case MouseWheelMoved(_, p, _, r) => wheel(r, p)
        case e@MouseClicked(_, p, m, n, _) => click(p, m, n, e)
      }

      def dummy(at: Point2D): Option[Dummy] =
        visualizer.model.visible_edges().map({
            i => visualizer.Coordinates(i).zipWithIndex.map((i, _))
          }).flatten.find({
            case (_, ((x, y), _)) =>
              visualizer.Drawer.shape(visualizer.gfx, None).contains(at.getX() - x, at.getY() - y)
          }) match {
            case None => None
            case Some((name, (_, index))) => Some((name, index))
          }

      def pressed(at: Point) {
        val c = Transform.pane_to_graph_coordinates(at)
        val l = node(c) match {
          case Some(l) =>
            if (visualizer.Selection(l)) visualizer.Selection() else List(l)
          case None => Nil
        }
        val d = l match {
          case Nil => dummy(c) match {
              case Some(d) => List(d)
              case None => Nil
          }

          case _ => Nil
        }

        draginfo = (at, l, d)
      }

      def click(at: Point, m: Modifiers, clicks: Int, e: MouseEvent) {
        import javax.swing.SwingUtilities

        val c = Transform.pane_to_graph_coordinates(at)
        val p = node(c)

        def left_click() {
          (p, m) match {
            case (Some(l), Control) => visualizer.Selection.add(l)
            case (None, Control) =>

            case (Some(l), Shift) => visualizer.Selection.add(l)
            case (None, Shift) =>

            case (Some(l), _) => visualizer.Selection.set(List(l))
            case (None, _) => visualizer.Selection.clear
          }
        }

        def right_click() {
          val menu = Popups(panel, p, visualizer.Selection())
          menu.show(panel.peer, at.x, at.y)
        }

        if (clicks < 2) {
          if (SwingUtilities.isRightMouseButton(e.peer)) right_click()
          else left_click()
        }
      }

      def drag(draginfo: (Point, Iterable[String], Iterable[Dummy]), to: Point) {
        val (from, p, d) = draginfo

        val s = Transform.scale_discrete
        val (dx, dy) = (to.x - from.x, to.y - from.y)
        (p, d) match {
          case (Nil, Nil) => {
            val r = panel.peer.getViewport.getViewRect
            r.translate(-dx, -dy)

            paint_panel.peer.scrollRectToVisible(r)
          }

          case (Nil, ds) =>
            ds.foreach(d => visualizer.Coordinates.translate(d, (dx / s, dy / s)))

          case (ls, _) =>
            ls.foreach(l => visualizer.Coordinates.translate(l, (dx / s, dy / s)))
        }
      }

      def wheel(rotation: Int, at: Point) {
        val at2 = Transform.pane_to_graph_coordinates(at)
        // > 0 -> up
        rescale(Transform.scale * (if (rotation > 0) 4.0/5 else 5.0/4))

        // move mouseposition to center
        Transform().transform(at2, at2)
        val r = panel.peer.getViewport.getViewRect
        val (width, height) = (r.getWidth, r.getHeight)
        paint_panel.peer.scrollRectToVisible(
          new Rectangle((at2.getX() - width / 2).toInt,
                        (at2.getY() - height / 2).toInt,
                        width.toInt,
                        height.toInt)
        )
      }
    }
  }
}
