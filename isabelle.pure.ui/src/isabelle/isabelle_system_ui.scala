/*  Copied from:
    Title:      Pure/System/isabelle_system.scala
    Author:     Makarius

Fundamental Isabelle system environment: quasi-static module with
optional init operation.
*/

package isabelle

import java.io.{FileInputStream, BufferedInputStream}
import java.awt.{GraphicsEnvironment, Font}
import java.awt.font.TextAttribute

object Isabelle_System_UI
{

  /* fonts */

  def get_font(family: String = "IsabelleText", size: Int = 1, bold: Boolean = false): Font =
    new Font(family, if (bold) Font.BOLD else Font.PLAIN, size)

  def install_fonts()
  {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    for (font <- Path.split(Isabelle_System.getenv_strict("ISABELLE_FONTS")))
      ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, font.file))
  }

  
  def install_fonts_jfx()
  {
    for (font <- Path.split(Isabelle_System.getenv_strict("ISABELLE_FONTS"))) {
      val stream = new BufferedInputStream(new FileInputStream(font.file))
      try { javafx.scene.text.Font.loadFont(stream, 1.0) }
      finally { stream.close }
    }
  }
}
