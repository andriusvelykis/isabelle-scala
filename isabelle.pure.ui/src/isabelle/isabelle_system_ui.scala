/*  Copied from:
    Title:      Pure/System/isabelle_system.scala
    Author:     Makarius

Fundamental Isabelle system environment: quasi-static module with
optional init operation.
*/

package isabelle

import java.io.{FileInputStream, BufferedInputStream}


object Isabelle_System_UI
{

  def install_fonts_jfx()
  {
    for (font <- Path.split(Isabelle_System.getenv_strict("ISABELLE_FONTS"))) {
      val stream = new BufferedInputStream(new FileInputStream(font.file))
      try { javafx.scene.text.Font.loadFont(stream, 1.0) }
      finally { stream.close }
    }
  }
}
