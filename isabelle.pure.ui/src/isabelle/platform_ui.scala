/*  Copied from:
    Title:      Pure/System/platform.scala
    Module:     PIDE
    Author:     Makarius

Raw platform identification.
*/

package isabelle

import javax.swing.UIManager


object Platform_UI
{

  /* Swing look-and-feel */

  private def find_laf(name: String): Option[String] =
    UIManager.getInstalledLookAndFeels().find(_.getName == name).map(_.getClassName)

  def get_laf(): String =
  {
    if (Platform.is_windows || Platform.is_macos) UIManager.getSystemLookAndFeelClassName()
    else
      find_laf("Nimbus") orElse find_laf("GTK+") getOrElse
      UIManager.getCrossPlatformLookAndFeelClassName()
  }

  def init_laf(): Unit = UIManager.setLookAndFeel(get_laf())
}

