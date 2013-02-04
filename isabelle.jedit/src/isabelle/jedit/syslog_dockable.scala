/*  Title:      Tools/jEdit/src/syslog_dockable.scala
    Author:     Makarius

Dockable window for syslog.
*/

package isabelle.jedit


import isabelle._

import scala.actors.Actor._
import scala.swing.TextArea

import org.gjt.sp.jedit.View


class Syslog_Dockable(view: View, position: String) extends Dockable(view, position)
{
  /* GUI components */

  private val syslog = new TextArea()

  private def update_syslog()
  {
    Swing_Thread.later {
      val text = PIDE.session.current_syslog()
      if (text != syslog.text) syslog.text = text
    }
  }

  set_content(syslog)


  /* main actor */

  private val main_actor = actor {
    loop {
      react {
        case output: Isabelle_Process.Output =>
          if (output.is_syslog) update_syslog()

        case bad => java.lang.System.err.println("Syslog_Dockable: ignoring bad message " + bad)
      }
    }
  }

  override def init()
  {
    PIDE.session.syslog_messages += main_actor
    update_syslog()
  }

  override def exit()
  {
    PIDE.session.syslog_messages -= main_actor
  }
}
