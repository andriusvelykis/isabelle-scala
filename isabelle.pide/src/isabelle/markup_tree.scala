/*  Title:      Pure/PIDE/markup_tree.scala
    Module:     PIDE
    Author:     Fabian Immler, TU Munich
    Author:     Makarius

Markup trees over nested / non-overlapping text ranges.
*/

package isabelle


import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.annotation.tailrec


object Markup_Tree
{
  /* construct trees */

  val empty: Markup_Tree = new Markup_Tree(Branches.empty)

  def merge_disjoint(trees: List[Markup_Tree]): Markup_Tree =
    trees match {
      case Nil => empty
      case head :: tail =>
        new Markup_Tree(
          (head.branches /: tail) {
            case (branches, tree) =>
              (branches /: tree.branches) {
                case (bs, (r, entry)) =>
                  require(!bs.isDefinedAt(r))
                  bs + (r -> entry)
              }
          })
    }


  /* tree building blocks */

  object Elements
  {
    val empty = new Elements(Set.empty)
  }

  final class Elements private(private val rep: Set[String])
  {
    def contains(name: String): Boolean = rep.contains(name)

    def + (name: String): Elements =
      if (contains(name)) this
      else new Elements(rep + name)

    def + (elem: XML.Elem): Elements = this + elem.markup.name
    def ++ (elems: Iterable[XML.Elem]): Elements = (this /: elems.iterator)(_ + _)

    def ++ (other: Elements): Elements =
      if (this eq other) this
      else if (rep.isEmpty) other
      else (this /: other.rep)(_ + _)
  }

  object Entry
  {
    def apply(markup: Text.Markup, subtree: Markup_Tree): Entry =
      Entry(markup.range, List(markup.info), Elements.empty + markup.info,
        subtree, subtree.make_elements)

    def apply(range: Text.Range, rev_markups: List[XML.Elem], subtree: Markup_Tree): Entry =
      Entry(range, rev_markups, Elements.empty ++ rev_markups,
        subtree, subtree.make_elements)
  }

  sealed case class Entry(
    range: Text.Range,
    rev_markup: List[XML.Elem],
    elements: Elements,
    subtree: Markup_Tree,
    subtree_elements: Elements)
  {
    def markup: List[XML.Elem] = rev_markup.reverse

    def + (markup: Text.Markup): Entry =
      copy(rev_markup = markup.info :: rev_markup, elements = elements + markup.info)

    def \ (markup: Text.Markup): Entry =
      copy(subtree = subtree + markup, subtree_elements = subtree_elements + markup.info)
  }

  object Branches
  {
    type T = SortedMap[Text.Range, Entry]
    val empty: T = SortedMap.empty(Text.Range.Ordering)
  }


  /* XML representation */

  @tailrec private def strip_elems(
      elems: List[XML.Elem], body: XML.Body): (List[XML.Elem], XML.Body) =
    body match {
      case List(XML.Wrapped_Elem(markup1, body1, body2)) =>
        strip_elems(XML.Elem(markup1, body1) :: elems, body2)
      case List(XML.Elem(markup1, body1)) =>
        strip_elems(XML.Elem(markup1, Nil) :: elems, body1)
      case _ => (elems, body)
    }

  private def make_trees(acc: (Int, List[Markup_Tree]), tree: XML.Tree): (Int, List[Markup_Tree]) =
    {
      val (offset, markup_trees) = acc

      strip_elems(Nil, List(tree)) match {
        case (Nil, body) =>
          (offset + XML.text_length(body), markup_trees)

        case (elems, body) =>
          val (end_offset, subtrees) = ((offset, Nil: List[Markup_Tree]) /: body)(make_trees)
          if (offset == end_offset) acc
          else {
            val range = Text.Range(offset, end_offset)
            val entry = Entry(range, elems, merge_disjoint(subtrees))
            (end_offset, new Markup_Tree(Branches.empty, entry) :: markup_trees)
          }
      }
    }

  def from_XML(body: XML.Body): Markup_Tree =
    merge_disjoint(((0, Nil: List[Markup_Tree]) /: body)(make_trees)._2)
}


final class Markup_Tree private(val branches: Markup_Tree.Branches.T)
{
  import Markup_Tree._

  private def this(branches: Markup_Tree.Branches.T, entry: Markup_Tree.Entry) =
    this(branches + (entry.range -> entry))

  override def toString =
    branches.toList.map(_._2) match {
      case Nil => "Empty"
      case list => list.mkString("Tree(", ",", ")")
    }

  private def overlapping(range: Text.Range): Branches.T =
  {
    val start = Text.Range(range.start)
    val stop = Text.Range(range.stop)
    val bs = branches.range(start, stop)
    branches.get(stop) match {
      case Some(end) if range overlaps end.range => bs + (end.range -> end)
      case _ => bs
    }
  }

  def make_elements: Elements =
    (Elements.empty /: branches)(
      { case (elements, (_, entry)) => elements ++ entry.subtree_elements ++ entry.elements })

  def + (new_markup: Text.Markup): Markup_Tree =
  {
    val new_range = new_markup.range

    branches.get(new_range) match {
      case None => new Markup_Tree(branches, Entry(new_markup, empty))
      case Some(entry) =>
        if (entry.range == new_range)
          new Markup_Tree(branches, entry + new_markup)
        else if (entry.range.contains(new_range))
          new Markup_Tree(branches, entry \ new_markup)
        else if (new_range.contains(branches.head._1) && new_range.contains(branches.last._1))
          new Markup_Tree(Branches.empty, Entry(new_markup, this))
        else {
          val body = overlapping(new_range)
          if (body.forall(e => new_range.contains(e._1)))
            new Markup_Tree(branches -- body.keys, Entry(new_markup, new Markup_Tree(body)))
          else {
            java.lang.System.err.println("Ignored overlapping markup information: " + new_markup +
              body.filter(e => !new_range.contains(e._1)).mkString("\n"))
            this
          }
        }
    }
  }

  def ++ (other: Markup_Tree): Markup_Tree =
    (this /: other.branches)({ case (tree, (range, entry)) =>
      ((tree ++ entry.subtree) /: entry.markup)({ case (t, elem) => t + Text.Info(range, elem) }) })

  def to_XML(root_range: Text.Range, text: CharSequence, filter: XML.Elem => Boolean): XML.Body =
  {
    def make_text(start: Text.Offset, stop: Text.Offset): XML.Body =
      if (start == stop) Nil
      else List(XML.Text(text.subSequence(start, stop).toString))

    def make_elems(rev_markups: List[XML.Elem], body: XML.Body): XML.Body =
      (body /: rev_markups) {
        case (b, elem) =>
          if (!filter(elem)) b
          else if (elem.body.isEmpty) List(XML.Elem(elem.markup, b))
          else List(XML.Wrapped_Elem(elem.markup, elem.body, b))
      }

    def make_body(elem_range: Text.Range, elem_markup: List[XML.Elem], entries: Branches.T)
      : XML.Body =
    {
      val body = new mutable.ListBuffer[XML.Tree]
      var last = elem_range.start
      for ((range, entry) <- entries) {
        val subrange = range.restrict(elem_range)
        body ++= make_text(last, subrange.start)
        body ++= make_body(subrange, entry.rev_markup, entry.subtree.overlapping(subrange))
        last = subrange.stop
      }
      body ++= make_text(last, elem_range.stop)
      make_elems(elem_markup, body.toList)
    }
   make_body(root_range, Nil, overlapping(root_range))
  }

  def to_XML(text: CharSequence): XML.Body =
    to_XML(Text.Range(0, text.length), text, (_: XML.Elem) => true)

  def cumulate[A](root_range: Text.Range, root_info: A, result_elements: Option[Set[String]],
    result: (A, Text.Markup) => Option[A]): List[Text.Info[A]] =
  {
    val notable: Elements => Boolean =
      result_elements match {
        case Some(res) => (elements: Elements) => res.exists(elements.contains)
        case None => (elements: Elements) => true
      }

    def results(x: A, entry: Entry): Option[A] =
    {
      var y = x
      var changed = false
      for {
        info <- entry.rev_markup // FIXME proper cumulation order (including status markup) (!?)
        y1 <- result(y, Text.Info(entry.range, info))
      } { y = y1; changed = true }
      if (changed) Some(y) else None
    }

    def traverse(
      last: Text.Offset,
      stack: List[(Text.Info[A], List[(Text.Range, Entry)])]): List[Text.Info[A]] =
    {
      stack match {
        case (parent, (range, entry) :: more) :: rest =>
          val subrange = range.restrict(root_range)
          val subtree =
            if (notable(entry.subtree_elements))
              entry.subtree.overlapping(subrange).toList
            else Nil
          val start = subrange.start

          (if (notable(entry.elements)) results(parent.info, entry) else None) match {
            case Some(res) =>
              val next = Text.Info(subrange, res)
              val nexts = traverse(start, (next, subtree) :: (parent, more) :: rest)
              if (last < start) parent.restrict(Text.Range(last, start)) :: nexts
              else nexts
            case None => traverse(last, (parent, subtree ::: more) :: rest)
          }

        case (parent, Nil) :: rest =>
          val stop = parent.range.stop
          val nexts = traverse(stop, rest)
          if (last < stop) parent.restrict(Text.Range(last, stop)) :: nexts
          else nexts

        case Nil =>
          val stop = root_range.stop
          if (last < stop) List(Text.Info(Text.Range(last, stop), root_info))
          else Nil
      }
    }
    traverse(root_range.start,
      List((Text.Info(root_range, root_info), overlapping(root_range).toList)))
  }
}

