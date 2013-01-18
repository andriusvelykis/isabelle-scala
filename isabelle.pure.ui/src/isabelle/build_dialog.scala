/*  Title:      Pure/Tools/build_dialog.scala
    Author:     Makarius

Dialog for session build process.
*/

package isabelle


import java.awt.{GraphicsEnvironment, Point, Font}

import scala.swing.{ScrollPane, Button, CheckBox, FlowPanel,
  BorderPanel, MainFrame, TextArea, SwingApplication}
import scala.swing.event.ButtonClicked


object Build_Dialog
{
  def main(args: Array[String]) =
  {
    Platform.init_laf()
    try {
      args.toList match {
        case
          logic_option ::
          logic ::
          Properties.Value.Boolean(system_mode) ::
          include_dirs =>
            val more_dirs = include_dirs.map(s => ((false, Path.explode(s))))

            val options = Options.init()
            val session =
              Isabelle_System.default_logic(logic,
                if (logic_option != "") options.string(logic_option) else "")

            if (Build.build(Build.Ignore_Progress, options, build_heap = true, no_build = true,
                more_dirs = more_dirs, sessions = List(session)) == 0) sys.exit(0)
            else
              Swing_Thread.later {
                val top = build_dialog(options, system_mode, more_dirs, session)
                top.pack()

                val point = GraphicsEnvironment.getLocalGraphicsEnvironment().getCenterPoint()
                top.location =
                  new Point(point.x - top.size.width / 2, point.y - top.size.height / 2)

                top.visible = true
              }
        case _ => error("Bad arguments:\n" + cat_lines(args))
      }
    }
    catch {
      case exn: Throwable =>
        Library.error_dialog(null, "Isabelle build failure",
          Library.scrollable_text(Exn.message(exn)))
        sys.exit(2)
    }
  }


  def build_dialog(
    options: Options,
    system_mode: Boolean,
    more_dirs: List[(Boolean, Path)],
    session: String): MainFrame = new MainFrame
  {
    iconImage = Isabelle_System.get_icon().getImage


    /* GUI state */

    private var is_stopped = false
    private var return_code = 2

    override def closeOperation { sys.exit(return_code) }


    /* text */

    val text = new TextArea {
      font = new Font("SansSerif", Font.PLAIN, Library.resolution_scale(10) max 14)
      editable = false
      columns = 50
      rows = 20
    }

    val scroll_text = new ScrollPane(text)

    val progress = new Build.Progress
    {
      override def echo(msg: String): Unit =
        Swing_Thread.later {
          text.append(msg + "\n")
          val vertical = scroll_text.peer.getVerticalScrollBar
          vertical.setValue(vertical.getMaximum)
        }
      override def theory(session: String, theory: String): Unit =
        echo(session + ": theory " + theory)
      override def stopped: Boolean =
        Swing_Thread.now { val b = is_stopped; is_stopped = false; b  }
    }


    /* action panel */

    var do_auto_close = true
    def check_auto_close(): Unit = if (do_auto_close && return_code == 0) sys.exit(return_code)

    val auto_close = new CheckBox("Auto close") {
      reactions += {
        case ButtonClicked(_) => do_auto_close = this.selected
        check_auto_close()
      }
    }
    auto_close.selected = do_auto_close
    auto_close.tooltip = "Automatically close dialog when finished"


    var button_action: () => Unit = (() => is_stopped = true)
    val button = new Button("Cancel") {
      reactions += { case ButtonClicked(_) => button_action() }
    }

    val delay_button_exit =
      Swing_Thread.delay_first(Time.seconds(1.0))
      {
        check_auto_close()
        button.text = if (return_code == 0) "OK" else "Exit"
        button_action = (() => sys.exit(return_code))
        button.peer.getRootPane.setDefaultButton(button.peer)
      }


    val action_panel = new FlowPanel(FlowPanel.Alignment.Center)(button, auto_close)


    /* layout panel */

    val layout_panel = new BorderPanel
    layout_panel.layout(scroll_text) = BorderPanel.Position.Center
    layout_panel.layout(action_panel) = BorderPanel.Position.South

    contents = layout_panel


    /* main build */

    title = "Isabelle build (" + Isabelle_System.getenv("ML_IDENTIFIER") + ")"
    progress.echo("Build started for Isabelle/" + session + " ...")

    default_thread_pool.submit(() => {
      val (out, rc) =
        try {
          ("",
            Build.build(progress, options, build_heap = true, more_dirs = more_dirs,
              system_mode = system_mode, sessions = List(session)))
        }
        catch { case exn: Throwable => (Exn.message(exn) + "\n", 2) }
      Swing_Thread.now {
        progress.echo(out + (if (rc == 0) "OK\n" else "Return code: " + rc + "\n"))
        return_code = rc
        delay_button_exit.invoke()
      }
    })
  }
}

