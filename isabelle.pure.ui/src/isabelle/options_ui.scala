/*  Copied from:
    Title:      Pure/System/options.scala
    Author:     Makarius

Stand-alone options with external string representation.
*/

package isabelle


class Options_Variable
{
  // owned by Swing thread
  @volatile private var options = Options.empty

  def value: Options = options
  def update(new_options: Options)
  {
    Swing_Thread.require()
    options = new_options
  }

  def + (name: String, x: String)
  {
    Swing_Thread.require()
    options = options + (name, x)
  }

  class Bool_Access
  {
    def apply(name: String): Boolean = options.bool(name)
    def update(name: String, x: Boolean)
    {
      Swing_Thread.require()
      options = options.bool.update(name, x)
    }
  }
  val bool = new Bool_Access

  class Int_Access
  {
    def apply(name: String): Int = options.int(name)
    def update(name: String, x: Int)
    {
      Swing_Thread.require()
      options = options.int.update(name, x)
    }
  }
  val int = new Int_Access

  class Real_Access
  {
    def apply(name: String): Double = options.real(name)
    def update(name: String, x: Double)
    {
      Swing_Thread.require()
      options = options.real.update(name, x)
    }
  }
  val real = new Real_Access

  class String_Access
  {
    def apply(name: String): String = options.string(name)
    def update(name: String, x: String)
    {
      Swing_Thread.require()
      options = options.string.update(name, x)
    }
  }
  val string = new String_Access

  class Seconds_Access
  {
    def apply(name: String): Time = options.seconds(name)
  }
  val seconds = new Seconds_Access
}

