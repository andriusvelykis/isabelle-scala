/*  Title:      Pure/Tools/sledgehammer_params.scala
    Author:     Makarius

Protocol for Sledgehammer parameters from ML (see also
HOL/Tools/Sledgehammer/sledgehammer_isar.ML).  */

package isabelle


object Sledgehammer_Params
{
  /* global event bus */

  case object Provers
  val provers = new Event_Bus[Provers.type]


  /* per session result */

  def get_provers(session: Session): String =
    session.protocol_handler("isabelle.Sledgehammer_Params$Handler") match {
      case Some(handler: Handler) => handler.get_provers
      case _ => ""
    }


  /* handler */

  class Handler extends Session.Protocol_Handler
  {
    private var _provers: String = ""
    private def update_provers(s: String)
    {
      synchronized { _provers = s }
      provers.event(Provers)
    }
    def get_provers: String = synchronized { _provers }

    private def sledgehammer_provers(
      prover: Session.Prover, output: Isabelle_Process.Output): Boolean =
    {
      output.body match {
        case Nil => update_provers(""); true
        case List(XML.Text(s)) => update_provers(s); true
        case _ => false
      }
    }

    val functions =
      Map(Markup.SLEDGEHAMMER_PROVERS -> sledgehammer_provers _)
  }
}