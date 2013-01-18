/*  Copied from:
    Title:      Pure/PIDE/markup_tree.scala
    Module:     PIDE
    Author:     Fabian Immler, TU Munich
    Author:     Makarius

Markup trees over nested / non-overlapping text ranges.
*/

package isabelle

import java.lang.System
import javax.swing.tree.DefaultMutableTreeNode

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.annotation.tailrec


object Markup_Tree_UI
{

  def swing_tree(tree: Markup_Tree,
    parent: DefaultMutableTreeNode,
    swing_node: Text.Info[List[XML.Elem]] => DefaultMutableTreeNode)
  {
    for ((_, entry) <- tree.branches) {
      val node = swing_node(Text.Info(entry.range, entry.markup))
      swing_tree(entry.subtree, node, swing_node)
      parent.add(node)
    }
  }
}

