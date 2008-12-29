/*  Title:      Pure/General/xml.scala
    Author:     Makarius

Simple XML tree values.
*/

package isabelle

import org.w3c.dom.{Node, Document}
import javax.xml.parsers.DocumentBuilderFactory


object XML
{
  /* datatype representation */

  type Attributes = List[(String, String)]

  abstract class Tree {
    override def toString = {
      val s = new StringBuilder
      append_tree(this, s)
      s.toString
    }
  }
  case class Elem(name: String, attributes: Attributes, body: List[Tree]) extends Tree
  case class Text(content: String) extends Tree


  /* string representation */

  private def append_text(text: String, s: StringBuilder) {
    for (c <- text.elements) c match {
      case '<' => s.append("&lt;")
      case '>' => s.append("&gt;")
      case '&' => s.append("&amp;")
      case '"' => s.append("&quot;")
      case '\'' => s.append("&apos;")
      case _ => s.append(c)
    }
  }

  private def append_elem(name: String, atts: Attributes, s: StringBuilder) {
    s.append(name)
    for ((a, x) <- atts) {
      s.append(" "); s.append(a); s.append("=\""); append_text(x, s); s.append("\"")
    }
  }

  private def append_tree(tree: Tree, s: StringBuilder) {
    tree match {
      case Elem(name, atts, Nil) =>
        s.append("<"); append_elem(name, atts, s); s.append("/>")
      case Elem(name, atts, ts) =>
        s.append("<"); append_elem(name, atts, s); s.append(">")
        for (t <- ts) append_tree(t, s)
        s.append("</"); s.append(name); s.append(">")
      case Text(text) => append_text(text, s)
    }
  }


  /* iterate over content */

  private type State = Option[(String, List[Tree])]

  private def get_next(tree: Tree): State = tree match {
    case Elem(_, _, body) => get_nexts(body)
    case Text(content) => Some(content, Nil)
  }
  private def get_nexts(trees: List[Tree]): State = trees match {
    case Nil => None
    case t :: ts => get_next(t) match {
      case None => get_nexts(ts)
      case Some((s, r)) => Some((s, r ++ ts))
    }
  }

  def content(tree: Tree) = new Iterator[String] {
    private var state = get_next(tree)
    def hasNext() = state.isDefined
    def next() = state match {
      case Some((s, rest)) => { state = get_nexts(rest); s }
      case None => throw new NoSuchElementException("next on empty iterator")
    }
  }


  /* document object model (DOM) */

  def document(tree: Tree, styles: String*) = {
    val doc = DocumentBuilderFactory.newInstance.newDocumentBuilder.newDocument
    doc.appendChild(doc.createProcessingInstruction("xml", "version=\"1.0\""))

    for (style <- styles) {
      doc.appendChild(doc.createProcessingInstruction("xml-stylesheet",
        "href=\"" + style + "\" type=\"text/css\""))
    }

    // main body
    def DOM(tr: Tree): Node = tr match {
      case Elem(name, atts, ts) => {
        val node = doc.createElement(name)
        for ((name, value) <- atts) node.setAttribute(name, value)
        for (t <- ts) node.appendChild(DOM(t))
        node
      }
      case Text(txt) => doc.createTextNode(txt)
    }
    val root_elem = tree match {
      case Elem(_, _, _) => DOM(tree)
      case Text(_) => DOM(Elem(Markup.ROOT, Nil, List(tree)))
    }
    doc.appendChild(root_elem)
    doc
  }
}
