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
  val options = PIDE.options
  protected val components =
    options.make_components(List(Isabelle_Logic.logic_selector(false)),
      (for ((name, opt) <- options.value.options.iterator if opt.public) yield name).toSet)
}


class Isabelle_Options2 extends Isabelle_Options("isabelle-rendering")
{
  private val predefined =
    (for {
      (name, opt) <- PIDE.options.value.options.toList
      if (name.endsWith("_color") && opt.section == JEdit_Options.RENDERING_SECTION)
    } yield PIDE.options.make_color_component(opt))

  assert(!predefined.isEmpty)

  protected val components = PIDE.options.make_components(predefined, _ => false)
}

