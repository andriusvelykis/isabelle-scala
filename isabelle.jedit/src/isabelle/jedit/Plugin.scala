/*
 * Main Isabelle/jEdit plugin setup
 *
 * @author Johannes Hölzl, TU Munich
 * @author Fabian Immler, TU Munich
 */

package isabelle.jedit


import java.io.{FileInputStream, IOException}
import java.awt.Font
import javax.swing.JScrollPane

import scala.collection.mutable

import isabelle.prover.{Prover, Command}
import isabelle.IsabelleSystem

import org.gjt.sp.jedit.{jEdit, EBMessage, EBPlugin, Buffer, EditPane, ServiceManager, View}
import org.gjt.sp.jedit.buffer.JEditBuffer
import org.gjt.sp.jedit.textarea.JEditTextArea
import org.gjt.sp.jedit.msg.{EditPaneUpdate, PropertiesChanged}


object Isabelle {
  // name
  val NAME = "Isabelle"
  val VFS_PREFIX = "isabelle:"

  // properties
  object Property {
    private val OPTION_PREFIX = "options.isabelle."
    def apply(name: String) = jEdit.getProperty(OPTION_PREFIX + name)
    def update(name: String, value: String) = jEdit.setProperty(OPTION_PREFIX + name, value)
  }

  // Isabelle system instance
  var system: IsabelleSystem = null
  def symbols = system.symbols

  // plugin instance
  var plugin: Plugin = null

  // running provers
  def prover_setup(buffer: JEditBuffer) = plugin.prover_setup(buffer)
}


class Plugin extends EBPlugin {

  // Isabelle font

  var font: Font = null
  val font_changed = new EventBus[Font]

  def set_font(path: String, size: Float) {
    font = Font.createFont(Font.TRUETYPE_FONT, new FileInputStream(path)).
      deriveFont(Font.PLAIN, size)
    font_changed.event(font)
  }


  /* unique ids */  // FIXME specific to "session" (!??)

  private var id_count: BigInt = 0
  def id() : String = synchronized { id_count += 1; "editor:" + id_count }


  // mapping buffer <-> prover

  private val mapping = new mutable.HashMap[JEditBuffer, ProverSetup]

  private def install(view: View) {
    val buffer = view.getBuffer
    val prover_setup = new ProverSetup(buffer)
    mapping.update(buffer, prover_setup)
    prover_setup.activate(view)
  }

  private def uninstall(view: View) =
    mapping.removeKey(view.getBuffer).get.deactivate

  def switch_active (view : View) =
    if (mapping.isDefinedAt(view.getBuffer)) uninstall(view)
    else install(view)

  def prover_setup (buffer : JEditBuffer) : Option[ProverSetup] = mapping.get(buffer)
  def is_active (buffer : JEditBuffer) = mapping.isDefinedAt(buffer)
  
  
  // main plugin plumbing

  override def handleMessage(msg: EBMessage) = msg match {
    case epu: EditPaneUpdate => epu.getWhat match {
      case EditPaneUpdate.BUFFER_CHANGED =>
        mapping get epu.getEditPane.getBuffer match {
          //only activate 'isabelle'-buffers!
          case None =>
          case Some(prover_setup) => 
            prover_setup.theory_view.activate
            val dockable = epu.getEditPane.getView.getDockableWindowManager.getDockable("isabelle-output")
            if(dockable != null) {
              val output_dockable = dockable.asInstanceOf[OutputDockable]
              if(output_dockable.getComponent(0) != prover_setup.output_text_view ) {
                output_dockable.asInstanceOf[OutputDockable].removeAll
                output_dockable.asInstanceOf[OutputDockable].add(new JScrollPane(prover_setup.output_text_view))
                output_dockable.revalidate
              }
            }
        }
      case EditPaneUpdate.BUFFER_CHANGING =>
        val buffer = epu.getEditPane.getBuffer
        if(buffer != null) mapping get buffer match {
          //only deactivate 'isabelle'-buffers!
          case None =>
          case Some(prover_setup) => prover_setup.theory_view.deactivate
        }
      case _ =>
    }
    case _ =>
  }

  override def start() {
    Isabelle.system = new IsabelleSystem
    Isabelle.plugin = this
    
    if (Isabelle.Property("font-path") != null && Isabelle.Property("font-size") != null)
      try {
        set_font(Isabelle.Property("font-path"), Isabelle.Property("font-size").toFloat)
      }
      catch {
        case e: NumberFormatException =>
      }
  }
  
  override def stop() {
    // TODO: proper cleanup
    Isabelle.system = null
    Isabelle.plugin = null
  }
}
