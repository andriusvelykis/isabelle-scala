/*  Title:      Tools/jEdit/src/jedit_thy_load.scala
    Author:     Makarius

Primitives for loading theory files, based on jEdit buffer content.
*/

package isabelle.jedit


import isabelle._

import java.io.{File => JFile, IOException}
import javax.swing.text.Segment

import org.gjt.sp.jedit.io.{VFS, FileVFS, VFSFile, VFSManager}
import org.gjt.sp.jedit.MiscUtilities
import org.gjt.sp.jedit.{View, Buffer}


class JEdit_Thy_Load(loaded_theories: Set[String] = Set.empty, base_syntax: Outer_Syntax)
  extends Thy_Load(loaded_theories, base_syntax)
{
  /* document node names */

  def buffer_node_dummy(buffer: Buffer): Option[Document.Node.Name] =
    Some(Document.Node.Name(JEdit_Lib.buffer_name(buffer), buffer.getDirectory, buffer.getName))

  def buffer_node_name(buffer: Buffer): Option[Document.Node.Name] =
  {
    val name = JEdit_Lib.buffer_name(buffer)
    Thy_Header.thy_name(name).map(theory => Document.Node.Name(name, buffer.getDirectory, theory))
  }


  /* file-system operations */

  override def append(dir: String, source_path: Path): String =
  {
    val path = source_path.expand
    if (path.is_absolute) Isabelle_System.platform_path(path)
    else {
      val vfs = VFSManager.getVFSForPath(dir)
      if (vfs.isInstanceOf[FileVFS])
        MiscUtilities.resolveSymlinks(
          vfs.constructPath(dir, Isabelle_System.platform_path(path)))
      else vfs.constructPath(dir, Isabelle_System.standard_path(path))
    }
  }

  override def with_thy_text[A](name: Document.Node.Name, f: CharSequence => A): A =
  {
    Swing_Thread.now {
      JEdit_Lib.jedit_buffer(name.node) match {
        case Some(buffer) =>
          JEdit_Lib.buffer_lock(buffer) {
            Some(f(buffer.getSegment(0, buffer.getLength)))
          }
        case None => None
      }
    } getOrElse {
      val file = new JFile(name.node)  // FIXME load URL via jEdit VFS (!?)
      if (!file.exists || !file.isFile) error("No such file: " + quote(file.toString))
      f(File.read(file))
    }
  }

  def check_file(view: View, path: String): Boolean =
  {
    val vfs = VFSManager.getVFSForPath(path)
    val session = vfs.createVFSSession(path, view)

    try {
      session != null && {
        try {
          val file = vfs._getFile(session, path, view)
          file != null && file.isReadable && file.getType == VFSFile.FILE
        }
        catch { case _: IOException => false }
      }
    }
    finally {
      try { vfs._endVFSSession(session, view) }
      catch { case _: IOException => }
    }
  }
}

