/*  Title:      Pure/PIDE/editor.scala
    Author:     Makarius

General editor operations.
*/

package isabelle


abstract class Editor[Context]
{
  def session: Session
  def flush(): Unit
  def current_context: Context
  def current_node(context: Context): Option[Document.Node.Name]
  def current_node_snapshot(context: Context): Option[Document.Snapshot]
  def node_snapshot(name: Document.Node.Name): Document.Snapshot
  def current_command(context: Context, snapshot: Document.Snapshot): Option[(Command, Text.Offset)]

  def node_overlays(name: Document.Node.Name): Document.Node.Overlays
  def insert_overlay(command: Command, fn: String, args: List[String]): Unit
  def remove_overlay(command: Command, fn: String, args: List[String]): Unit

  abstract class Hyperlink { def follow(context: Context): Unit }
  def hyperlink_file(file_name: String, line: Int = 0, column: Int = 0): Hyperlink
  def hyperlink_command(
    snapshot: Document.Snapshot, command: Command, offset: Text.Offset = 0): Option[Hyperlink]
}

