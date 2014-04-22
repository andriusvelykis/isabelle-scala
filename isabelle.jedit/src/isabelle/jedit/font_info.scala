/*  Title:      Tools/jEdit/src/font_info.scala
    Author:     Makarius

Font information, derived from main jEdit view font.
*/

package isabelle.jedit


import isabelle._


import java.awt.Font

import org.gjt.sp.jedit.{jEdit, View}


object Font_Info
{
  /* size range */

  val min_size = 5
  val max_size = 250

  def restrict_size(size: Float): Float = size max min_size min max_size


  /* main jEdit font */

  def main_family(): String = jEdit.getProperty("view.font")

  def main_size(scale: Double = 1.0): Float =
    restrict_size(jEdit.getIntegerProperty("view.fontsize", 16).toFloat * scale.toFloat)

  def main(scale: Double = 1.0): Font_Info =
    Font_Info(main_family(), main_size(scale))


  /* incremental size change */

  object main_change
  {
    private def change_size(change: Float => Float)
    {
      Swing_Thread.require {}

      val size0 = main_size()
      val size = restrict_size(change(size0)).round
      if (size != size0) {
        jEdit.setIntegerProperty("view.fontsize", size)
        jEdit.propertiesChanged()
        jEdit.saveSettings()
        jEdit.getActiveView().getStatus.setMessageAndClear("Text font size: " + size)
      }
    }

    // owned by Swing thread
    private var steps = 0
    private val delay = Swing_Thread.delay_last(PIDE.options.seconds("editor_input_delay"))
    {
      change_size(size =>
        {
          var i = size.round
          while (steps != 0 && i > 0) {
            if (steps > 0)
              { i += (i / 10) max 1; steps -= 1 }
            else
              { i -= (i / 10) max 1; steps += 1 }
          }
          steps = 0
          i.toFloat
        })
    }

    def step(i: Int)
    {
      steps += i
      delay.invoke()
    }

    def reset(size: Float)
    {
      delay.revoke()
      steps = 0
      change_size(_ => size)
    }
  }
}

sealed case class Font_Info(family: String, size: Float)
{
  def font: Font = new Font(family, Font.PLAIN, size.round)
}

