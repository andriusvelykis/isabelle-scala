/*  Copied from: 
    Title:      Pure/General/pretty.scala
    Author:     Makarius

Generic pretty printing module.
*/

package isabelle


import java.awt.FontMetrics


object Pretty_UI
{

  /* formatted output */

  def char_width(metrics: FontMetrics): Double = metrics.stringWidth("mix").toDouble / 3
  def char_width_int(metrics: FontMetrics): Int = char_width(metrics).round.toInt

  def font_metric(metrics: FontMetrics): String => Double =
    if (metrics == null) ((s: String) => s.length.toDouble)
    else {
      val unit = char_width(metrics)
      ((s: String) => if (s == "\n") 1.0 else metrics.stringWidth(s) / unit)
    }

}
