/*  Title:      Tools/jEdit/src/jedit_options.scala
    Author:     Makarius

Options for Isabelle/jEdit.
*/

package isabelle.jedit


import isabelle._

import java.awt.Color
import javax.swing.{InputVerifier, JComponent, UIManager}
import javax.swing.text.JTextComponent

import scala.swing.{Component, CheckBox, TextArea}

import org.gjt.sp.jedit.gui.ColorWellButton


trait Option_Component extends Component
{
  val title: String
  def load(): Unit
  def save(): Unit
}

class JEdit_Options extends Options_Variable
{
  def color_value(s: String): Color = Color_Value(string(s))

  def make_color_component(opt: Options.Opt): Option_Component =
  {
    Swing_Thread.require()

    val opt_name = opt.name
    val opt_title = opt.title("jedit")

    val button = new ColorWellButton(Color_Value(opt.value))
    val component = new Component with Option_Component {
      override lazy val peer = button
      name = opt_name
      val title = opt_title
      def load = button.setSelectedColor(Color_Value(string(opt_name)))
      def save = string(opt_name) = Color_Value.print(button.getSelectedColor)
    }
    component.tooltip = opt.print_default
    component
  }

  def make_component(opt: Options.Opt): Option_Component =
  {
    Swing_Thread.require()

    val opt_name = opt.name
    val opt_title = opt.title("jedit")

    val component =
      if (opt.typ == Options.Bool)
        new CheckBox with Option_Component {
          name = opt_name
          val title = opt_title
          def load = selected = bool(opt_name)
          def save = bool(opt_name) = selected
        }
      else {
        val default_font = UIManager.getFont("TextField.font")
        val text_area =
          new TextArea with Option_Component {
            if (default_font != null) font = default_font
            name = opt_name
            val title = opt_title
            def load = text = value.check_name(opt_name).value
            def save =
              try { update(value + (opt_name, text)) }
              catch {
                case ERROR(msg) =>
                  Library.error_dialog(this.peer, "Failed to update options",
                    Library.scrollable_text(msg))
              }
          }
        text_area.peer.setInputVerifier(new InputVerifier {
          def verify(jcomponent: JComponent): Boolean =
            jcomponent match {
              case text: JTextComponent =>
                try { value + (opt_name, text.getText); true }
                catch { case ERROR(_) => false }
              case _ => true
            }
          })
        text_area
      }
    component.load()
    component.tooltip = opt.print_default
    component
  }

  def make_components(predefined: List[Option_Component], filter: String => Boolean)
    : List[(String, List[Option_Component])] =
  {
    def mk_component(opt: Options.Opt): List[Option_Component] =
      predefined.find(opt.name == _.name) match {
        case Some(c) => List(c)
        case None => if (filter(opt.name)) List(make_component(opt)) else Nil
      }
    value.sections.sortBy(_._1).map(
        { case (a, opts) => (a, opts.sortBy(_.title("jedit")).flatMap(mk_component _)) })
      .filterNot(_._2.isEmpty)
  }
}

