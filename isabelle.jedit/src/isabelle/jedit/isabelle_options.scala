/*  Title:      Tools/jEdit/src/isabelle_options.scala
    Author:     Johannes Hölzl, TU Munich

Editor pane for plugin options.
*/

package isabelle.jedit


import isabelle._

import javax.swing.JSpinner

import scala.swing.CheckBox

import org.gjt.sp.jedit.AbstractOptionPane


class Isabelle_Options extends AbstractOptionPane("isabelle")
{
  private val logic_selector = Isabelle.logic_selector(Isabelle.Property("logic"))
  private val auto_start = new CheckBox()
  private val relative_font_size = new JSpinner()
  private val tooltip_font_size = new JSpinner()
  private val tooltip_margin = new JSpinner()
  private val tooltip_dismiss_delay = new JSpinner()

  override def _init()
  {
    addComponent(Isabelle.Property("logic.title"), logic_selector.peer)

    addComponent(Isabelle.Property("auto-start.title"), auto_start.peer)
    auto_start.selected = Isabelle.Boolean_Property("auto-start")

    relative_font_size.setValue(Isabelle.Int_Property("relative-font-size", 100))
    addComponent(Isabelle.Property("relative-font-size.title"), relative_font_size)

    tooltip_font_size.setValue(Isabelle.Int_Property("tooltip-font-size", 10))
    addComponent(Isabelle.Property("tooltip-font-size.title"), tooltip_font_size)

    tooltip_margin.setValue(Isabelle.Int_Property("tooltip-margin", 40))
    addComponent(Isabelle.Property("tooltip-margin.title"), tooltip_margin)

    tooltip_dismiss_delay.setValue(
      Isabelle.Time_Property("tooltip-dismiss-delay", Time.seconds(8.0)).ms.toInt)
    addComponent(Isabelle.Property("tooltip-dismiss-delay.title"), tooltip_dismiss_delay)
  }

  override def _save()
  {
    Isabelle.Property("logic") = logic_selector.selection.item.name

    Isabelle.Boolean_Property("auto-start") = auto_start.selected

    Isabelle.Int_Property("relative-font-size") =
      relative_font_size.getValue().asInstanceOf[Int]

    Isabelle.Int_Property("tooltip-font-size") =
      tooltip_font_size.getValue().asInstanceOf[Int]

    Isabelle.Int_Property("tooltip-margin") =
      tooltip_margin.getValue().asInstanceOf[Int]

    Isabelle.Time_Property("tooltip-dismiss-delay") =
      Time.seconds(tooltip_dismiss_delay.getValue().asInstanceOf[Int])
  }
}
