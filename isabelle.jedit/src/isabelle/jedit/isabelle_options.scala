/*  Title:      Tools/jEdit/src/isabelle_options.scala
    Author:     Makarius

Editor pane for plugin options.
*/

package isabelle.jedit


import isabelle._

import org.gjt.sp.jedit.{jEdit, AbstractOptionPane}


abstract class Isabelle_Options(name: String) extends AbstractOptionPane(name)
{
  protected val components: List[(String, List[Option_Component])]

  override def _init()
  {
    val dummy_property = "options.isabelle.dummy"

    for ((s, cs) <- components) {
      if (s != "") {
        jEdit.setProperty(dummy_property, s)
        addSeparator(dummy_property)
        jEdit.setProperty(dummy_property, null)
      }
      cs.foreach(c => addComponent(c.title, c.peer))
    }
  }

  override def _save()
  {
    for ((_, cs) <- components) cs.foreach(_.save())
  }
}


class Isabelle_Options1 extends Isabelle_Options("isabelle-general")
{
  // FIXME avoid hard-wired stuff
  private val relevant_options =
    Set("jedit_logic", "jedit_font_scale", "jedit_symbols_search_limit", "jedit_text_overview_limit",
      "jedit_tooltip_bounds", "jedit_tooltip_font_scale", "jedit_tooltip_margin",
      "jedit_mac_adapter", "threads", "threads_trace", "parallel_proofs",
      "parallel_proofs_threshold", "editor_load_delay", "editor_input_delay",
      "editor_output_delay", "editor_reparse_limit", "editor_tracing_messages",
      "editor_update_delay", "editor_chart_delay")

  relevant_options.foreach(PIDE.options.value.check_name _)

  protected val components =
    PIDE.options.make_components(List(Isabelle_Logic.logic_selector(false)), relevant_options)
}


class Isabelle_Options2 extends Isabelle_Options("isabelle-rendering")
{
  // FIXME avoid hard-wired stuff
  private val predefined =
    (for {
      (name, opt) <- PIDE.options.value.options.toList
      if (name.endsWith("_color") && opt.section == "Rendering of Document Content")
    } yield PIDE.options.make_color_component(opt))

  assert(!predefined.isEmpty)

  protected val components = PIDE.options.make_components(predefined, _ => false)
}

