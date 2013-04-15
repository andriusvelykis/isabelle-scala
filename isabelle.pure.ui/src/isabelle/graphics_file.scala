/*  Title:      Pure/General/graphics_file.scala
    Author:     Makarius

File system operations for Graphics2D output.
*/

package isabelle


import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.io.{FileOutputStream, BufferedOutputStream, File => JFile}

import org.jfree.chart.JFreeChart


object Graphics_File
{
  /* PDF */

  def write_pdf(file: JFile, paint: Graphics2D => Unit, width: Int, height: Int)
  {
    import com.lowagie.text.{Document, Rectangle}
    import com.lowagie.text.pdf.PdfWriter

    val out = new BufferedOutputStream(new FileOutputStream(file))
    try {
      val document = new Document()
      try {
        document.setPageSize(new Rectangle(width, height))
        val writer = PdfWriter.getInstance(document, out)
        document.open()

        val cb = writer.getDirectContent()
        val tp = cb.createTemplate(width, height)
        val gfx = tp.createGraphics(width, height)

        paint(gfx)
        gfx.dispose

        cb.addTemplate(tp, 1, 0, 0, 1, 0, 0)
      }
      finally { document.close() }
    }
    finally { out.close }
  }

  def write_pdf(path: Path, paint: Graphics2D => Unit, width: Int, height: Int): Unit =
    write_pdf(path.file, paint, width, height)

  def write_pdf(file: JFile, chart: JFreeChart, width: Int, height: Int)
  {
    def paint(gfx: Graphics2D) = chart.draw(gfx, new Rectangle2D.Double(0, 0, width, height))
    write_pdf(file, paint _, width, height)
  }

  def write_pdf(path: Path, chart: JFreeChart, width: Int, height: Int): Unit =
    write_pdf(path.file, chart, width, height)
}

