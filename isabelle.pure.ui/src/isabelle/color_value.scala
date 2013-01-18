/*  Title:      Pure/System/color_value.scala
    Module:     PIDE
    Author:     Makarius

Cached color values.
*/

package isabelle


import java.awt.Color


object Color_Value
{
  private var cache = Map.empty[String, Color]

  def parse(s: String): Color =
  {
    val i = java.lang.Long.parseLong(s, 16)
    val r = ((i >> 24) & 0xFF).toInt
    val g = ((i >> 16) & 0xFF).toInt
    val b = ((i >> 8) & 0xFF).toInt
    val a = (i & 0xFF).toInt
    new Color(r, g, b, a)
  }

  def print(c: Color): String =
  {
    val r = new java.lang.Integer(c.getRed)
    val g = new java.lang.Integer(c.getGreen)
    val b = new java.lang.Integer(c.getBlue)
    val a = new java.lang.Integer(c.getAlpha)
    String.format("%02x%02x%02x%02x", r, g, b, a).toUpperCase
  }

  def apply(s: String): Color =
    synchronized {
      cache.get(s) match {
        case Some(c) => c
        case None =>
          val c = parse(s)
          cache += (s -> c)
          c
      }
    }
}

