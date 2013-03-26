/*  Title:      Tools/jEdit/src/timing_dockable.scala
    Author:     Makarius

Dockable window for timing information.
*/

package isabelle.jedit


import isabelle._

import scala.actors.Actor._
import scala.swing.{FlowPanel, Label, ListView, Alignment, ScrollPane, Component, TextField}
import scala.swing.event.{EditDone, MouseClicked}

import java.lang.System
import java.awt.{BorderLayout, Graphics2D, Insets}
import javax.swing.{JList, BorderFactory}
import javax.swing.border.{BevelBorder, SoftBevelBorder}

import org.gjt.sp.jedit.{View, jEdit}


class Timing_Dockable(view: View, position: String) extends Dockable(view, position)
{
  /* entry */

  private object Entry
  {
    object Ordering extends scala.math.Ordering[Entry]
    {
      def compare(entry1: Entry, entry2: Entry): Int =
        entry2.timing compare entry1.timing
    }

    object Renderer_Component extends Label
    {
      opaque = false
      xAlignment = Alignment.Leading
      border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
    }

    class Renderer extends ListView.Renderer[Entry]
    {
      def componentFor(list: ListView[_], isSelected: Boolean, focused: Boolean,
        entry: Entry, index: Int): Component =
      {
        val component = Renderer_Component
        component.text = entry.print
        component
      }
    }
  }

  private abstract class Entry
  {
    def timing: Double
    def print: String
    def follow(snapshot: Document.Snapshot)
  }

  private case class Theory_Entry(name: Document.Node.Name, timing: Double) extends Entry
  {
    def print: String = Time.print_seconds(timing) + "s theory " + quote(name.theory)
    def follow(snapshot: Document.Snapshot): Unit = Hyperlink(name.node).follow(view)
  }

  private case class Command_Entry(command: Command, timing: Double) extends Entry
  {
    def print: String = "  " + Time.print_seconds(timing) + "s command " + quote(command.name)

    def follow(snapshot: Document.Snapshot)
    {
      val node = snapshot.version.nodes(command.node_name)
      if (node.commands.contains(command)) {
        val sources = node.commands.iterator.takeWhile(_ != command).map(_.source)
        val (line, column) = ((1, 1) /: sources)(Symbol.advance_line_column)
        Hyperlink(command.node_name.node, line, column).follow(view)
      }
    }
  }


  /* timing view */

  private val timing_view = new ListView(Nil: List[Entry]) {
    listenTo(mouse.clicks)
    reactions += {
      case MouseClicked(_, point, _, clicks, _) if clicks == 2 =>
        val index = peer.locationToIndex(point)
        if (index >= 0) listData(index).follow(PIDE.session.snapshot())
    }
  }
  timing_view.peer.setLayoutOrientation(JList.VERTICAL_WRAP)
  timing_view.peer.setVisibleRowCount(0)
  timing_view.selection.intervalMode = ListView.IntervalMode.Single
  timing_view.renderer = new Entry.Renderer

  set_content(new ScrollPane(timing_view))


  /* timing threshold */

  private var timing_threshold = PIDE.options.real("jedit_timing_threshold")

  private val threshold_label = new Label("Threshold: ")

  private val threshold_value = new TextField(Time.print_seconds(timing_threshold)) {
    reactions += {
      case EditDone(_) =>
        text match {
          case Properties.Value.Double(x) if x >= 0.0 => timing_threshold = x
          case _ =>
        }
        text = Time.print_seconds(timing_threshold)
        handle_update()
    }
    tooltip = "Threshold for timing display (seconds)"
  }

  private val controls = new FlowPanel(FlowPanel.Alignment.Right)(threshold_label, threshold_value)
  add(controls.peer, BorderLayout.NORTH)


  /* component state -- owned by Swing thread */

  private var nodes_timing = Map.empty[Document.Node.Name, Protocol.Node_Timing]

  private def make_entries(): List[Entry] =
  {
    Swing_Thread.require()

    val name =
      Document_View(view.getTextArea) match {
        case None => Document.Node.Name.empty
        case Some(doc_view) => doc_view.model.name
      }
    val timing = nodes_timing.get(name) getOrElse Protocol.empty_node_timing

    val theories =
      (for {
        (node_name, node_timing) <- nodes_timing.toList
        if node_timing.total > timing_threshold
      } yield Theory_Entry(node_name, node_timing.total)).sorted(Entry.Ordering)
    val commands =
      (for ((command, command_timing) <- timing.commands.toList)
        yield Command_Entry(command, command_timing)).sorted(Entry.Ordering)

    theories.flatMap(entry => entry :: (if (entry.name == name) commands else Nil))
  }

  private def handle_update(restriction: Option[Set[Document.Node.Name]] = None)
  {
    Swing_Thread.now {
      val snapshot = PIDE.session.snapshot()

      val iterator =
        restriction match {
          case Some(names) => names.iterator.map(name => (name, snapshot.version.nodes(name)))
          case None => snapshot.version.nodes.entries
        }
      val nodes_timing1 =
        (nodes_timing /: iterator)({ case (timing1, (name, node)) =>
            if (PIDE.thy_load.loaded_theories(name.theory)) timing1
            else {
              val node_timing =
                Protocol.node_timing(snapshot.state, snapshot.version, node, timing_threshold)
              timing1 + (name -> node_timing)
            }
        })
      nodes_timing = nodes_timing1

      val entries = make_entries()
      if (timing_view.listData.toList != entries) timing_view.listData = entries
    }
  }


  /* main actor */

  private val main_actor = actor {
    loop {
      react {
        case changed: Session.Commands_Changed => handle_update(Some(changed.nodes))

        case bad => System.err.println("Timing_Dockable: ignoring bad message " + bad)
      }
    }
  }

  override def init()
  {
    PIDE.session.commands_changed += main_actor
    handle_update()
  }

  override def exit()
  {
    PIDE.session.commands_changed -= main_actor
  }
}
