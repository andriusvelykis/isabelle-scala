/*  Adapted from:
    Title:      Pure/ML/ml_statistics.ML
    Author:     Makarius

ML runtime statistics.
*/

package isabelle


import scala.swing.{Frame, Component}

import org.jfree.data.xy.{XYSeries, XYSeriesCollection}
import org.jfree.chart.{JFreeChart, ChartPanel, ChartFactory}
import org.jfree.chart.plot.PlotOrientation


object ML_Statistics_UI
{

  /* charts */

  def update_data(content: List[ML_Statistics.Entry], 
                  data: XYSeriesCollection,
                  selected_fields: Iterable[String])
  {
    data.removeAllSeries
    for {
      field <- selected_fields.iterator
      series = new XYSeries(field)
    } {
      content.foreach(entry => series.add(entry.time, entry.data(field)))
      data.addSeries(series)
    }
  }

  def chart(content: List[ML_Statistics.Entry],
            title: String,
            selected_fields: Iterable[String]): JFreeChart =
  {
    val data = new XYSeriesCollection
    update_data(content, data, selected_fields)

    ChartFactory.createXYLineChart(title, "time", "value", data,
      PlotOrientation.VERTICAL, true, true, true)
  }

  def chart(arg: (String, Iterable[String])): JFreeChart = chart(arg._1, arg._2)

  def standard_frames: Unit =
    ML_Statistics.standard_fields.map(chart(_)).foreach(c =>
      Swing_Thread.later {
        new Frame {
          iconImage = toolkit.getImage(Isabelle_System.get_icon())
          title = name
          contents = Component.wrap(new ChartPanel(c))
          visible = true
        }
      })
}

