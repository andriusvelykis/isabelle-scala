/*  Title:      Tools/jEdit/src/active.scala
    Author:     Makarius

Active areas within the document.
*/

package isabelle.jedit


import isabelle._

import org.gjt.sp.jedit.View


object Active
{
  def action(view: View, text: String, elem: XML.Elem)
  {
    Swing_Thread.require()

    Document_View(view.getTextArea) match {
      case Some(doc_view) =>
        doc_view.rich_text_area.robust_body() {
          val text_area = doc_view.text_area
          val model = doc_view.model
          val buffer = model.buffer
          val snapshot = model.snapshot()

          if (!snapshot.is_outdated) {
            // FIXME avoid hard-wired stuff
            elem match {
              case XML.Elem(Markup(Markup.BROWSER, _), body) =>
                default_thread_pool.submit(() =>
                  {
                    val graph_file = File.tmp_file("graph")
                    File.write(graph_file, XML.content(body))
                    Isabelle_System.bash_env(null,
                      Map("GRAPH_FILE" -> Isabelle_System.posix_path(graph_file)),
                      "\"$ISABELLE_TOOL\" browser -c \"$GRAPH_FILE\" &")
                  })

              case XML.Elem(Markup(Markup.GRAPHVIEW, _), body) =>
                default_thread_pool.submit(() =>
                  {
                    val graph =
                      Exn.capture {
                        isabelle.graphview.Model.decode_graph(body)
                          .transitive_reduction_acyclic
                      }
                    Swing_Thread.later { Graphview_Dockable(view, snapshot, graph) }
                  })

              case XML.Elem(Markup(Markup.SENDBACK, props), _) =>
                props match {
                  case Position.Id(id) =>
                    Isabelle.edit_command(snapshot, buffer,
                      props.exists(_ == Markup.PADDING_COMMAND), id, text)
                  case _ =>
                    if (props.exists(_ == Markup.PADDING_LINE))
                      Isabelle.insert_line_padding(text_area, text)
                    else text_area.setSelectedText(text)
                }

              case Protocol.Dialog(id, serial, result) =>
                model.session.dialog_result(id, serial, result)

              case _ =>
            }
          }
        }
      case None =>
    }
  }
}

