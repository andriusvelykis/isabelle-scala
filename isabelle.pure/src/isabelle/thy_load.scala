/*  Title:      Pure/Thy/thy_load.scala
    Author:     Makarius

Primitives for loading theory files.
*/

package isabelle


import scala.annotation.tailrec

import java.io.{File => JFile}


object Thy_Load
{
  /* paths */

  def thy_path(path: Path): Path = path.ext("thy")

  def is_ok(str: String): Boolean =
    try { thy_path(Path.explode(str)); true }
    catch { case ERROR(_) => false }


  /* document node names */

  def path_node_name(raw_path: Path): Document.Node.Name =
  {
    val path = raw_path.expand
    val node = path.implode
    val dir = path.dir.implode
    val theory = Thy_Header.thy_name(node) getOrElse error("Bad theory file name: " + path)
    Document.Node.Name(node, dir, theory)
  }
}


class Thy_Load(val loaded_theories: Set[String] = Set.empty, val base_syntax: Outer_Syntax)
{
  /* file-system operations */

  def append(dir: String, source_path: Path): String =
    (Path.explode(dir) + source_path).expand.implode

  def with_thy_text[A](name: Document.Node.Name, f: CharSequence => A): A =
  {
    val path = Path.explode(name.node)
    if (!path.is_file) error("No such file: " + path.toString)

    val text = File.read(path)
    Symbol.decode_strict(text)
    f(text)
  }


  /* theory files */

  def body_files_test(syntax: Outer_Syntax, text: String): Boolean =
    syntax.thy_load_commands.exists({ case (cmd, _) => text.containsSlice(cmd) })

  def body_files(syntax: Outer_Syntax, text: String): List[String] =
  {
    val spans = Thy_Syntax.parse_spans(syntax.scan(text))
    spans.iterator.map(Thy_Syntax.span_files(syntax, _)).flatten.toList
  }

  def import_name(master: Document.Node.Name, s: String): Document.Node.Name =
  {
    val theory = Thy_Header.base_name(s)
    if (loaded_theories(theory)) Document.Node.Name(theory, "", theory)
    else {
      val path = Path.explode(s)
      val node = append(master.dir, Thy_Load.thy_path(path))
      val dir = append(master.dir, path.dir)
      Document.Node.Name(node, dir, theory)
    }
  }

  def check_thy_text(name: Document.Node.Name, text: CharSequence): Document.Node.Header =
  {
    try {
      val header = Thy_Header.read(text)

      val name1 = header.name
      if (name.theory != name1)
        error("Bad file name " + Thy_Load.thy_path(Path.basic(name.theory)) +
          " for theory " + quote(name1))

      val imports = header.imports.map(import_name(name, _))
      Document.Node.Header(imports, header.keywords, Nil)
    }
    catch { case exn: Throwable => Document.Node.bad_header(Exn.message(exn)) }
  }

  def check_thy(name: Document.Node.Name): Document.Node.Header =
    with_thy_text(name, check_thy_text(name, _))


  /* theory text edits */

  def text_edits(reparse_limit: Int, previous: Document.Version, edits: List[Document.Edit_Text])
      : (List[Document.Edit_Command], Document.Version) =
    Thy_Syntax.text_edits(base_syntax, reparse_limit, previous, edits)
}

