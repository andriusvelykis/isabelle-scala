/*  Title:      Tools/jEdit/src/output_dockable.scala
    Author:     Makarius

Dockable window with result message output.
*/

package isabelle.jedit


import isabelle._

import scala.actors.Actor._

import scala.swing.{FlowPanel, Button, CheckBox}
import scala.swing.event.ButtonClicked

import java.lang.System
import java.awt.BorderLayout
import java.awt.event.{ComponentEvent, ComponentAdapter}

import org.gjt.sp.jedit.View


class Output_Dockable(view: View, position: String) extends Dockable(view, position)
{
  Swing_Thread.require()


  /* component state -- owned by Swing thread */

  private var zoom_factor = 100
  private var do_update = true
  private var current_snapshot = Document.State.init.snapshot()
  private var current_state = Command.empty.init_state
  private var current_output: List[XML.Tree] = Nil


  /* pretty text area */

  val pretty_text_area = new Pretty_Text_Area(view)
  set_content(pretty_text_area)


  private def handle_resize()
  {
    Swing_Thread.require()

    pretty_text_area.resize(Rendering.font_family(),
      (Rendering.font_size("jedit_font_scale") * zoom_factor / 100).round)
  }

  private def handle_update(follow: Boolean, restriction: Option[Set[Command]])
  {
    Swing_Thread.require()

    val (new_snapshot, new_state) =
      Document_View(view.getTextArea) match {
        case Some(doc_view) =>
          val snapshot = doc_view.model.snapshot()
          if (follow && !snapshot.is_outdated) {
            snapshot.node.command_at(doc_view.text_area.getCaretPosition).map(_._1) match {
              case Some(cmd) =>
                (snapshot, snapshot.state.command_state(snapshot.version, cmd))
              case None =>
                (Document.State.init.snapshot(), Command.empty.init_state)
            }
          }
          else (current_snapshot, current_state)
        case None => (current_snapshot, current_state)
      }

    val new_output =
      if (!restriction.isDefined || restriction.get.contains(new_state.command)) {
        val rendering = Rendering(new_snapshot, PIDE.options.value)
        rendering.output_messages(new_state)
      }
      else current_output

    if (new_output != current_output)
      pretty_text_area.update(new_snapshot, new_state.results, Pretty.separate(new_output))

    current_snapshot = new_snapshot
    current_state = new_state
    current_output = new_output
  }


  /* main actor */

  private val main_actor = actor {
    loop {
      react {
        case _: Session.Global_Options =>
          Swing_Thread.later { handle_resize() }
        case changed: Session.Commands_Changed =>
          val restriction = if (changed.assignment) None else Some(changed.commands)
          Swing_Thread.later { handle_update(do_update, restriction) }
        case Session.Caret_Focus =>
          Swing_Thread.later { handle_update(do_update, None) }
        case bad => System.err.println("Output_Dockable: ignoring bad message " + bad)
      }
    }
  }

  override def init()
  {
    Swing_Thread.require()

    PIDE.session.global_options += main_actor
    PIDE.session.commands_changed += main_actor
    PIDE.session.caret_focus += main_actor
    handle_update(true, None)
  }

  override def exit()
  {
    Swing_Thread.require()

    PIDE.session.global_options -= main_actor
    PIDE.session.commands_changed -= main_actor
    PIDE.session.caret_focus -= main_actor
    delay_resize.revoke()
  }


  /* resize */

  private val delay_resize =
    Swing_Thread.delay_first(PIDE.options.seconds("editor_update_delay")) { handle_resize() }

  addComponentListener(new ComponentAdapter {
    override def componentResized(e: ComponentEvent) { delay_resize.invoke() }
  })


  /* controls */

  private val zoom = new Library.Zoom_Box(factor => { zoom_factor = factor; handle_resize() })
  zoom.tooltip = "Zoom factor for basic font size"

  private val auto_update = new CheckBox("Auto update") {
    reactions += {
      case ButtonClicked(_) => do_update = this.selected; handle_update(do_update, None) }
  }
  auto_update.selected = do_update
  auto_update.tooltip = "Indicate automatic update following cursor movement"

  private val update = new Button("Update") {
    reactions += { case ButtonClicked(_) => handle_update(true, None) }
  }
  update.tooltip = "Update display according to the command at cursor position"

  private val detach = new Button("Detach") {
    reactions += {
      case ButtonClicked(_) =>
        Info_Dockable(view, current_snapshot,
          current_state.results, Pretty.separate(current_output))
    }
  }
  detach.tooltip = "Detach window with static copy of current output"

  private val controls = new FlowPanel(FlowPanel.Alignment.Right)(zoom, auto_update, update, detach)
  add(controls.peer, BorderLayout.NORTH)
}
