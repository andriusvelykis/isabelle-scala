/*  Title:      Tools/jEdit/src/raw_output_dockable.scala
    Author:     Makarius

Dockable window for raw process output (stdout).
*/

package isabelle.jedit


import isabelle._

import java.lang.System

import scala.actors.Actor._
import scala.swing.{TextArea, ScrollPane}

import org.gjt.sp.jedit.View


class Raw_Output_Dockable(view: View, position: String) extends Dockable(view, position)
{
  private val text_area = new TextArea
  set_content(new ScrollPane(text_area))


  /* main actor */

  private val main_actor = actor {
    loop {
      react {
        case output: Isabelle_Process.Output =>
          if (output.is_stdout || output.is_stderr)
            Swing_Thread.later { text_area.append(XML.content(output.message)) }

        case bad => System.err.println("Raw_Output_Dockable: ignoring bad message " + bad)
      }
    }
  }

  override def init() { PIDE.session.raw_output_messages += main_actor }
  override def exit() { PIDE.session.raw_output_messages -= main_actor }
}
