/*  Title:      Pure/PIDE/protocol.scala
    Author:     Makarius

Protocol message formats for interactive proof documents.
*/

package isabelle


object Protocol
{
  /* document editing */

  object Assign
  {
    def unapply(text: String): Option[(Document.Version_ID, Document.Assign)] =
      try {
        import XML.Decode._
        val body = YXML.parse_body(text)
        Some(pair(long, list(pair(long, option(long))))(body))
      }
      catch {
        case ERROR(_) => None
        case _: XML.XML_Atom => None
        case _: XML.XML_Body => None
      }
  }

  object Removed
  {
    def unapply(text: String): Option[List[Document.Version_ID]] =
      try {
        import XML.Decode._
        Some(list(long)(YXML.parse_body(text)))
      }
      catch {
        case ERROR(_) => None
        case _: XML.XML_Atom => None
        case _: XML.XML_Body => None
      }
  }


  /* command status */

  object Status
  {
    val init = Status()
  }

  sealed case class Status(
    private val touched: Boolean = false,
    private val accepted: Boolean = false,
    private val failed: Boolean = false,
    forks: Int = 0,
    runs: Int = 0)
  {
    def + (that: Status): Status =
      Status(touched || that.touched, accepted || that.accepted, failed || that.failed,
        forks + that.forks, runs + that.runs)

    def is_unprocessed: Boolean = accepted && !failed && (!touched || (forks != 0 && runs == 0))
    def is_running: Boolean = runs != 0
    def is_finished: Boolean = !failed && touched && forks == 0 && runs == 0
    def is_failed: Boolean = failed
  }

  val command_status_markup: Set[String] =
    Set(Markup.ACCEPTED, Markup.FORKED, Markup.JOINED, Markup.RUNNING,
      Markup.FINISHED, Markup.FAILED)

  def command_status(status: Status, markup: Markup): Status =
    markup match {
      case Markup(Markup.ACCEPTED, _) => status.copy(accepted = true)
      case Markup(Markup.FORKED, _) => status.copy(touched = true, forks = status.forks + 1)
      case Markup(Markup.JOINED, _) => status.copy(forks = status.forks - 1)
      case Markup(Markup.RUNNING, _) => status.copy(touched = true, runs = status.runs + 1)
      case Markup(Markup.FINISHED, _) => status.copy(runs = status.runs - 1)
      case Markup(Markup.FAILED, _) => status.copy(failed = true)
      case _ => status
    }

  def command_status(markups: List[Markup]): Status =
    (Status.init /: markups)(command_status(_, _))


  /* node status */

  sealed case class Node_Status(
    unprocessed: Int, running: Int, finished: Int, warned: Int, failed: Int)
  {
    def total: Int = unprocessed + running + finished + warned + failed
  }

  def node_status(
    state: Document.State, version: Document.Version, node: Document.Node): Node_Status =
  {
    var unprocessed = 0
    var running = 0
    var finished = 0
    var warned = 0
    var failed = 0
    node.commands.foreach(command =>
      {
        val st = state.command_state(version, command)
        val status = command_status(st.status)
        if (status.is_running) running += 1
        else if (status.is_finished) {
          if (st.results.entries.exists(p => is_warning(p._2))) warned += 1
          else finished += 1
        }
        else if (status.is_failed) failed += 1
        else unprocessed += 1
      })
    Node_Status(unprocessed, running, finished, warned, failed)
  }


  /* result messages */

  private val clean = Set(Markup.REPORT, Markup.NO_REPORT)

  def clean_message(body: XML.Body): XML.Body =
    body filter {
      case XML.Wrapped_Elem(Markup(name, _), _, _) => !clean(name)
      case XML.Elem(Markup(name, _), _) => !clean(name)
      case _ => true
    } map {
      case XML.Wrapped_Elem(markup, body, ts) => XML.Wrapped_Elem(markup, body, clean_message(ts))
      case XML.Elem(markup, ts) => XML.Elem(markup, clean_message(ts))
      case t => t
    }

  def message_reports(props: Properties.T, body: XML.Body): List[XML.Elem] =
    body flatMap {
      case XML.Wrapped_Elem(Markup(Markup.REPORT, ps), body, ts) =>
        List(XML.Wrapped_Elem(Markup(Markup.REPORT, props ::: ps), body, ts))
      case XML.Elem(Markup(Markup.REPORT, ps), ts) =>
        List(XML.Elem(Markup(Markup.REPORT, props ::: ps), ts))
      case XML.Wrapped_Elem(_, _, ts) => message_reports(props, ts)
      case XML.Elem(_, ts) => message_reports(props, ts)
      case XML.Text(_) => Nil
    }


  /* specific messages */

  def is_inlined(msg: XML.Tree): Boolean =
    !(is_result(msg) || is_tracing(msg) || is_state(msg))

  def is_result(msg: XML.Tree): Boolean =
    msg match {
      case XML.Elem(Markup(Markup.RESULT, _), _) => true
      case _ => false
    }

  def is_tracing(msg: XML.Tree): Boolean =
    msg match {
      case XML.Elem(Markup(Markup.TRACING, _), _) => true
      case XML.Elem(Markup(Markup.TRACING_MESSAGE, _), _) => true
      case _ => false
    }

  def is_state(msg: XML.Tree): Boolean =
    msg match {
      case XML.Elem(Markup(Markup.WRITELN, _),
        List(XML.Elem(Markup(Markup.STATE, _), _))) => true
      case XML.Elem(Markup(Markup.WRITELN_MESSAGE, _),
        List(XML.Elem(Markup(Markup.STATE, _), _))) => true
      case _ => false
    }

  def is_warning(msg: XML.Tree): Boolean =
    msg match {
      case XML.Elem(Markup(Markup.WARNING, _), _) => true
      case XML.Elem(Markup(Markup.WARNING_MESSAGE, _), _) => true
      case _ => false
    }

  def is_error(msg: XML.Tree): Boolean =
    msg match {
      case XML.Elem(Markup(Markup.ERROR, _), _) => true
      case XML.Elem(Markup(Markup.ERROR_MESSAGE, _), _) => true
      case _ => false
    }


  /* dialogs */

  object Dialog_Args
  {
    def unapply(props: Properties.T): Option[(Document.ID, Long, String)] =
      (props, props, props) match {
        case (Position.Id(id), Markup.Serial(serial), Markup.Result(result)) =>
          Some((id, serial, result))
        case _ => None
      }
  }

  object Dialog
  {
    def unapply(tree: XML.Tree): Option[(Document.ID, Long, String)] =
      tree match {
        case XML.Elem(Markup(Markup.DIALOG, Dialog_Args(id, serial, result)), _) =>
          Some((id, serial, result))
        case _ => None
      }
  }

  object Dialog_Result
  {
    def apply(id: Document.ID, serial: Long, result: String): XML.Elem =
    {
      val props = Position.Id(id) ::: Markup.Serial(serial)
      XML.Elem(Markup(Markup.RESULT, props), List(XML.Text(result)))
    }

    def unapply(tree: XML.Tree): Option[String] =
      tree match {
        case XML.Elem(Markup(Markup.RESULT, _), List(XML.Text(result))) => Some(result)
        case _ => None
      }
  }


  /* reported positions */

  private val include_pos = Set(Markup.BINDING, Markup.ENTITY, Markup.REPORT, Markup.POSITION)

  def message_positions(command: Command, message: XML.Elem): Set[Text.Range] =
  {
    def elem_positions(raw_range: Text.Range, set: Set[Text.Range], body: XML.Body)
      : Set[Text.Range] =
    {
      val range = command.decode(raw_range).restrict(command.range)
      body.foldLeft(if (range.is_singularity) set else set + range)(positions)
    }

    def positions(set: Set[Text.Range], tree: XML.Tree): Set[Text.Range] =
      tree match {
        case XML.Wrapped_Elem(Markup(name, Position.Id_Range(id, range)), _, body)
        if include_pos(name) && id == command.id => elem_positions(range, set, body)

        case XML.Elem(Markup(name, Position.Id_Range(id, range)), body)
        if include_pos(name) && id == command.id => elem_positions(range, set, body)

        case XML.Wrapped_Elem(_, _, body) => body.foldLeft(set)(positions)

        case XML.Elem(_, body) => body.foldLeft(set)(positions)

        case _ => set
      }

    val set = positions(Set.empty, message)
    if (set.isEmpty)
      set ++ Position.Range.unapply(message.markup.properties).map(command.decode(_))
    else set
  }
}


trait Protocol extends Isabelle_Process
{
  /* commands */

  def define_command(command: Command): Unit =
    input("Document.define_command",
      Document.ID(command.id), encode(command.name), encode(command.source))


  /* document versions */

  def discontinue_execution() { input("Document.discontinue_execution") }

  def cancel_execution() { input("Document.cancel_execution") }

  def update(old_id: Document.Version_ID, new_id: Document.Version_ID,
    edits: List[Document.Edit_Command])
  {
    val edits_yxml =
    { import XML.Encode._
      def id: T[Command] = (cmd => long(cmd.id))
      def encode_edit(name: Document.Node.Name)
          : T[Document.Node.Edit[(Option[Command], Option[Command]), Command.Perspective]] =
        variant(List(
          { case Document.Node.Clear() => (Nil, Nil) },  // FIXME unused !?
          { case Document.Node.Edits(a) => (Nil, list(pair(option(id), option(id)))(a)) },
          { case Document.Node.Deps(header) =>
              val dir = Isabelle_System.posix_path(name.dir)
              val imports = header.imports.map(_.node)
              val keywords = header.keywords.map({ case (a, b, _) => (a, b) })
              // FIXME val uses = deps.uses.map(p => (Isabelle_System.posix_path(p._1), p._2))
              val uses = header.uses
              (Nil,
                pair(Encode.string, pair(Encode.string, pair(list(Encode.string),
                  pair(list(pair(Encode.string,
                    option(pair(pair(Encode.string, list(Encode.string)), list(Encode.string))))),
                  pair(list(pair(Encode.string, bool)), list(Encode.string))))))(
                (dir, (name.theory, (imports, (keywords, (uses, header.errors))))))) },
          { case Document.Node.Perspective(a) => (a.commands.map(c => long_atom(c.id)), Nil) }))
      def encode_edits: T[List[Document.Edit_Command]] = list((node_edit: Document.Edit_Command) =>
      {
        val (name, edit) = node_edit
        pair(string, encode_edit(name))(name.node, edit)
      })
      YXML.string_of_body(encode_edits(edits)) }
    input("Document.update", Document.ID(old_id), Document.ID(new_id), edits_yxml)
  }

  def remove_versions(versions: List[Document.Version])
  {
    val versions_yxml =
      { import XML.Encode._
        YXML.string_of_body(list(long)(versions.map(_.id))) }
    input("Document.remove_versions", versions_yxml)
  }


  /* dialog via document content */

  def dialog_result(serial: Long, result: String)
  {
    input("Document.dialog_result", Properties.Value.Long(serial), result)
  }


  /* method invocation service */

  def invoke_scala(id: String, tag: Invoke_Scala.Tag.Value, res: String)
  {
    input("Document.invoke_scala", id, tag.toString, res)
  }
}
