/*  Title:      Tools/Graphview/src/shapes.scala
    Author:     Markus Kaiser, TU Muenchen

Drawable shapes.
*/

package isabelle.graphview


import java.awt.{BasicStroke, Graphics2D, Shape}
import java.awt.geom.{AffineTransform, GeneralPath, Path2D, Rectangle2D, PathIterator}


object Shapes
{
  trait Node
  {
    def shape(g: Graphics2D, visualizer: Visualizer, peer: Option[String]): Shape
    def paint(g: Graphics2D, visualizer: Visualizer, peer: Option[String]): Unit
  }

  object Growing_Node extends Node
  {
    private val stroke =
      new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)

    def shape(g: Graphics2D, visualizer: Visualizer, peer: Option[String]): Rectangle2D.Double =
    {
      val (x, y) = visualizer.Coordinates(peer.get)
      val bounds = g.getFontMetrics.getStringBounds(visualizer.Caption(peer.get), g)
      val w = bounds.getWidth + visualizer.pad_x
      val h = bounds.getHeight + visualizer.pad_y
      new Rectangle2D.Double(x - (w / 2), y - (h / 2), w, h)
    }

    def paint(g: Graphics2D, visualizer: Visualizer, peer: Option[String])
    {
      val caption = visualizer.Caption(peer.get)
      val bounds = g.getFontMetrics.getStringBounds(caption, g)
      val s = shape(g, visualizer, peer)

      val (border, background, foreground) = visualizer.Color(peer)
      g.setStroke(stroke)
      g.setColor(border)
      g.draw(s)
      g.setColor(background)
      g.fill(s)
      g.setColor(foreground)
      g.drawString(caption,
        (s.getCenterX + (- bounds.getWidth / 2)).toFloat,
        (s.getCenterY + 5).toFloat)
    }
  }

  object Dummy extends Node
  {
    private val stroke =
      new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
    private val shape = new Rectangle2D.Double(-5, -5, 10, 10)
    private val identity = new AffineTransform()

    def shape(g: Graphics2D, visualizer: Visualizer, peer: Option[String]): Shape = shape

    def paint(g: Graphics2D, visualizer: Visualizer, peer: Option[String]): Unit =
      paint_transformed(g, visualizer, peer, identity)

    def paint_transformed(g: Graphics2D, visualizer: Visualizer,
      peer: Option[String], at: AffineTransform)
    {
      val (border, background, foreground) = visualizer.Color(peer)
      g.setStroke(stroke)
      g.setColor(border)
      g.draw(at.createTransformedShape(shape))
    }
  }

  trait Edge
  {
    def paint(g: Graphics2D, visualizer: Visualizer,
      peer: (String, String), head: Boolean, dummies: Boolean)
  }

  object Straight_Edge extends Edge
  {
    private val stroke =
      new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)

    def paint(g: Graphics2D, visualizer: Visualizer,
      peer: (String, String), head: Boolean, dummies: Boolean)
    {
      val ((fx, fy), (tx, ty)) = (visualizer.Coordinates(peer._1), visualizer.Coordinates(peer._2))
      val ds =
      {
        val min = fy min ty
        val max = fy max ty
        visualizer.Coordinates(peer).filter({ case (_, y) => y > min && y < max })
      }
      val path = new GeneralPath(Path2D.WIND_EVEN_ODD, ds.length + 2)

      path.moveTo(fx, fy)
      ds.foreach({case (x, y) => path.lineTo(x, y)})
      path.lineTo(tx, ty)

      if (dummies) {
        ds.foreach({
            case (x, y) => {
              val at = AffineTransform.getTranslateInstance(x, y)
              Dummy.paint_transformed(g, visualizer, None, at)
            }
          })
      }

      g.setStroke(stroke)
      g.setColor(visualizer.Color(peer))
      g.draw(path)

      if (head) Arrow_Head.paint(g, path, visualizer.Drawer.shape(g, Some(peer._2)))
    }
  }

  object Cardinal_Spline_Edge extends Edge
  {
    private val stroke =
      new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
    private val slack = 0.1

    def paint(g: Graphics2D, visualizer: Visualizer,
      peer: (String, String), head: Boolean, dummies: Boolean)
    {
      val ((fx, fy), (tx, ty)) =
        (visualizer.Coordinates(peer._1), visualizer.Coordinates(peer._2))
      val ds =
      {
        val min = fy min ty
        val max = fy max ty
        visualizer.Coordinates(peer).filter({case (_, y) => y > min && y < max})
      }

      if (ds.isEmpty) Straight_Edge.paint(g, visualizer, peer, head, dummies)
      else {
        val path = new GeneralPath(Path2D.WIND_EVEN_ODD, ds.length + 2)
        path.moveTo(fx, fy)

        val coords = ((fx, fy) :: ds ::: List((tx, ty)))
        val (dx, dy) = (coords(2)._1 - coords(0)._1, coords(2)._2 - coords(0)._2)

        val (dx2, dy2) =
          ((dx, dy) /: coords.sliding(3))({
            case ((dx, dy), List((lx, ly), (mx, my), (rx, ry))) => {
              val (dx2, dy2) = (rx - lx, ry - ly)

              path.curveTo(lx + slack * dx , ly + slack * dy,
                           mx - slack * dx2, my - slack * dy2,
                           mx              , my)
              (dx2, dy2)
            }
          })

        val (lx, ly) = ds.last
        path.curveTo(lx + slack * dx2, ly + slack * dy2,
                     tx - slack * dx2, ty - slack * dy2,
                     tx              , ty)

        if (dummies) {
          ds.foreach({
              case (x, y) => {
                val at = AffineTransform.getTranslateInstance(x, y)
                Dummy.paint_transformed(g, visualizer, None, at)
              }
            })
        }

        g.setStroke(stroke)
        g.setColor(visualizer.Color(peer))
        g.draw(path)

        if (head) Arrow_Head.paint(g, path, visualizer.Drawer.shape(g, Some(peer._2)))
      }
    }
  }

  object Arrow_Head
  {
    type Point = (Double, Double)

    private def position(path: GeneralPath, shape: Shape): Option[AffineTransform] =
    {
      def intersecting_line(path: GeneralPath, shape: Shape): Option[(Point, Point)] =
      {
        val i = path.getPathIterator(null, 1.0)
        val seg = Array[Double](0,0,0,0,0,0)
        var p1 = (0.0, 0.0)
        var p2 = (0.0, 0.0)
        while (!i.isDone()) {
          i.currentSegment(seg) match {
            case PathIterator.SEG_MOVETO => p2 = (seg(0), seg(1))
            case PathIterator.SEG_LINETO =>
              p1 = p2
              p2 = (seg(0), seg(1))

              if(shape.contains(seg(0), seg(1)))
                return Some((p1, p2))
            case _ =>
          }
          i.next()
        }
        None
      }

      def binary_search(line: (Point, Point), shape: Shape): Option[AffineTransform] =
      {
        val ((fx, fy), (tx, ty)) = line
        if (shape.contains(fx, fy) == shape.contains(tx, ty))
          None
        else {
          val (dx, dy) = (fx - tx, fy - ty)
          if ((dx * dx + dy * dy) < 1.0) {
            val at = AffineTransform.getTranslateInstance(fx, fy)
            at.rotate(- (math.atan2(dx, dy) + math.Pi / 2))
            Some(at)
          }
          else {
            val (mx, my) = (fx + (tx - fx) / 2.0, fy + (ty - fy) / 2.0)
            if (shape.contains(fx, fy) == shape.contains(mx, my))
              binary_search(((mx, my), (tx, ty)), shape)
            else
              binary_search(((fx, fy), (mx, my)), shape)
          }
        }
      }

      intersecting_line(path, shape) match {
        case None => None
        case Some(line) => binary_search(line, shape)
      }
    }

    def paint(g: Graphics2D, path: GeneralPath, shape: Shape)
    {
      position(path, shape) match {
        case None =>
        case Some(at) =>
          val arrow = new GeneralPath(Path2D.WIND_EVEN_ODD, 3)
          arrow.moveTo(0, 0)
          arrow.lineTo(-10, 4)
          arrow.lineTo(-6, 0)
          arrow.lineTo(-10, -4)
          arrow.lineTo(0, 0)
          arrow.transform(at)

          g.fill(arrow)
      }
    }
  }
}