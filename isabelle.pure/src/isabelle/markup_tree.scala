/*  Title:      Pure/PIDE/markup_tree.scala
    Author:     Fabian Immler, TU Munich
    Author:     Makarius

Markup trees over nested / non-overlapping text ranges.
*/

package isabelle


import javax.swing.tree.DefaultMutableTreeNode

import scala.collection.immutable.SortedMap


object Markup_Tree
{
  /* branches sorted by quasi-order -- overlapping ranges appear as equivalent */

  object Branches
  {
    type Entry = (Text.Info[Any], Markup_Tree)
    type T = SortedMap[Text.Range, Entry]

    val empty = SortedMap.empty[Text.Range, Entry](new scala.math.Ordering[Text.Range]
      {
        def compare(r1: Text.Range, r2: Text.Range): Int = r1 compare r2
      })

    def update(branches: T, entry: Entry): T = branches + (entry._1.range -> entry)
    def single(entry: Entry): T = update(empty, entry)

    def overlapping(range: Text.Range, branches: T): T =  // FIXME special cases!?
    {
      val start = Text.Range(range.start)
      val stop = Text.Range(range.stop)
      val bs = branches.range(start, stop)
      branches.get(stop) match {
        case Some(end) if range overlaps end._1.range => update(bs, end)
        case _ => bs
      }
    }
  }

  val empty = new Markup_Tree(Branches.empty)

  type Select[A] = PartialFunction[Text.Info[Any], A]
}


case class Markup_Tree(val branches: Markup_Tree.Branches.T)
{
  import Markup_Tree._

  override def toString =
    branches.toList.map(_._2) match {
      case Nil => "Empty"
      case list => list.mkString("Tree(", ",", ")")
    }

  def + (new_info: Text.Info[Any]): Markup_Tree =
  {
    val new_range = new_info.range
    branches.get(new_range) match {
      case None =>
        new Markup_Tree(Branches.update(branches, new_info -> empty))
      case Some((info, subtree)) =>
        val range = info.range
        if (range.contains(new_range))
          new Markup_Tree(Branches.update(branches, info -> (subtree + new_info)))
        else if (new_range.contains(branches.head._1) && new_range.contains(branches.last._1))
          new Markup_Tree(Branches.single(new_info -> this))
        else {
          val body = Branches.overlapping(new_range, branches)
          if (body.forall(e => new_range.contains(e._1))) {
            val rest = // branches -- body, modulo workarounds for Redblack in Scala 2.8.0
              if (body.size > 1)
                (Branches.empty /: branches)((rest, entry) =>
                  if (body.isDefinedAt(entry._1)) rest else rest + entry)
              else branches
            new Markup_Tree(Branches.update(rest, new_info -> new Markup_Tree(body)))
          }
          else { // FIXME split markup!?
            System.err.println("Ignored overlapping markup information: " + new_info)
            this
          }
        }
    }
  }

  private def overlapping(range: Text.Range): Stream[(Text.Range, Branches.Entry)] =
    Branches.overlapping(range, branches).toStream

  def select[A](root_range: Text.Range)(result: Markup_Tree.Select[A])
    : Stream[Text.Info[Option[A]]] =
  {
    def stream(
      last: Text.Offset,
      stack: List[(Text.Info[Option[A]], Stream[(Text.Range, Branches.Entry)])])
        : Stream[Text.Info[Option[A]]] =
    {
      stack match {
        case (parent, (range, (info, tree)) #:: more) :: rest =>
          val subrange = range.restrict(root_range)
          val subtree = tree.overlapping(subrange)
          val start = subrange.start

          if (result.isDefinedAt(info)) {
            val next = Text.Info[Option[A]](subrange, Some(result(info)))
            val nexts = stream(start, (next, subtree) :: (parent, more) :: rest)
            if (last < start) parent.restrict(Text.Range(last, start)) #:: nexts
            else nexts
          }
          else stream(last, (parent, subtree #::: more) :: rest)

        case (parent, Stream.Empty) :: rest =>
          val stop = parent.range.stop
          val nexts = stream(stop, rest)
          if (last < stop) parent.restrict(Text.Range(last, stop)) #:: nexts
          else nexts

        case Nil =>
          val stop = root_range.stop
          if (last < stop) Stream(Text.Info(Text.Range(last, stop), None))
          else Stream.empty
      }
    }
    stream(root_range.start, List((Text.Info(root_range, None), overlapping(root_range))))
  }

  def swing_tree(parent: DefaultMutableTreeNode)
    (swing_node: Text.Info[Any] => DefaultMutableTreeNode)
  {
    for ((_, (info, subtree)) <- branches) {
      val current = swing_node(info)
      subtree.swing_tree(current)(swing_node)
      parent.add(current)
    }
  }
}

