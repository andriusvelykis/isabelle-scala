/*  Title:      Pure/PIDE/document.scala
    Author:     Makarius

Document as collection of named nodes, each consisting of an editable
list of commands, associated with asynchronous execution process.
*/

package isabelle


import scala.collection.mutable


object Document
{
  /* formal identifiers */

  type ID = Long
  val ID = Properties.Value.Long

  type Version_ID = ID
  type Command_ID = ID
  type Exec_ID = ID

  val no_id: ID = 0
  val new_id = new Counter



  /** document structure **/

  /* named nodes -- development graph */

  type Edit[A, B] = (String, Node.Edit[A, B])
  type Edit_Text = Edit[Text.Edit, Text.Perspective]
  type Edit_Command = Edit[(Option[Command], Option[Command]), Command.Perspective]

  type Node_Header = Exn.Result[Thy_Header]

  object Node
  {
    sealed abstract class Edit[A, B]
    {
      def foreach(f: A => Unit)
      {
        this match {
          case Edits(es) => es.foreach(f)
          case _ =>
        }
      }
    }
    case class Clear[A, B]() extends Edit[A, B]
    case class Edits[A, B](edits: List[A]) extends Edit[A, B]
    case class Header[A, B](header: Node_Header) extends Edit[A, B]
    case class Perspective[A, B](perspective: B) extends Edit[A, B]

    def norm_header[A, B](f: String => String, g: String => String, header: Node_Header)
        : Header[A, B] =
      header match {
        case Exn.Res(h) => Header[A, B](Exn.capture { h.norm_deps(f, g) })
        case exn => Header[A, B](exn)
      }

    val empty: Node = Node(Exn.Exn(ERROR("Bad theory header")), Map(), Linear_Set())

    def command_starts(commands: Iterator[Command], offset: Text.Offset = 0)
      : Iterator[(Command, Text.Offset)] =
    {
      var i = offset
      for (command <- commands) yield {
        val start = i
        i += command.length
        (command, start)
      }
    }
  }

  private val block_size = 1024

  sealed case class Node(
    val header: Node_Header,
    val blobs: Map[String, Blob],
    val commands: Linear_Set[Command])
  {
    /* commands */

    private lazy val full_index: (Array[(Command, Text.Offset)], Text.Range) =
    {
      val blocks = new mutable.ListBuffer[(Command, Text.Offset)]
      var next_block = 0
      var last_stop = 0
      for ((command, start) <- Node.command_starts(commands.iterator)) {
        last_stop = start + command.length
        while (last_stop + 1 > next_block) {
          blocks += (command -> start)
          next_block += block_size
        }
      }
      (blocks.toArray, Text.Range(0, last_stop))
    }

    def full_range: Text.Range = full_index._2

    def command_range(i: Text.Offset = 0): Iterator[(Command, Text.Offset)] =
    {
      if (!commands.isEmpty && full_range.contains(i)) {
        val (cmd0, start0) = full_index._1(i / block_size)
        Node.command_starts(commands.iterator(cmd0), start0) dropWhile {
          case (cmd, start) => start + cmd.length <= i }
      }
      else Iterator.empty
    }

    def command_range(range: Text.Range): Iterator[(Command, Text.Offset)] =
      command_range(range.start) takeWhile { case (_, start) => start < range.stop }

    def command_at(i: Text.Offset): Option[(Command, Text.Offset)] =
    {
      val range = command_range(i)
      if (range.hasNext) Some(range.next) else None
    }

    def proper_command_at(i: Text.Offset): Option[Command] =
      command_at(i) match {
        case Some((command, _)) =>
          commands.reverse_iterator(command).find(cmd => !cmd.is_ignored)
        case None => None
      }

    def command_start(cmd: Command): Option[Text.Offset] =
      command_starts.find(_._1 == cmd).map(_._2)

    def command_starts: Iterator[(Command, Text.Offset)] =
      Node.command_starts(commands.iterator)
  }



  /** versioning **/

  /* particular document versions */

  object Version
  {
    val init: Version = Version(no_id, Map().withDefaultValue(Node.empty))
  }

  sealed case class Version(val id: Version_ID, val nodes: Map[String, Node])


  /* changes of plain text, eventually resulting in document edits */

  object Change
  {
    val init = Change(Future.value(Version.init), Nil, Future.value(Version.init))
  }

  sealed case class Change(
    val previous: Future[Version],
    val edits: List[Edit_Text],
    val version: Future[Version])
  {
    def is_finished: Boolean = previous.is_finished && version.is_finished
  }


  /* history navigation */

  object History
  {
    val init = new History(List(Change.init))
  }

  // FIXME pruning, purging of state
  class History(val undo_list: List[Change])
  {
    require(!undo_list.isEmpty)

    def tip: Change = undo_list.head
    def +(change: Change): History = new History(change :: undo_list)
  }



  /** global state -- document structure, execution process, editing history **/

  abstract class Snapshot
  {
    val version: Version
    val node: Node
    val is_outdated: Boolean
    def lookup_command(id: Command_ID): Option[Command]
    def state(command: Command): Command.State
    def convert(i: Text.Offset): Text.Offset
    def convert(range: Text.Range): Text.Range
    def revert(i: Text.Offset): Text.Offset
    def revert(range: Text.Range): Text.Range
    def select_markup[A](range: Text.Range)(result: Markup_Tree.Select[A])
      : Stream[Text.Info[Option[A]]]
  }

  object State
  {
    class Fail(state: State) extends Exception

    val init = State().define_version(Version.init, Map()).assign(Version.init.id, Nil)._2

    case class Assignment(
      val assignment: Map[Command, Exec_ID],
      val is_finished: Boolean = false)
    {
      def get_finished: Map[Command, Exec_ID] = { require(is_finished); assignment }
      def assign(command_execs: List[(Command, Exec_ID)]): Assignment =
      {
        require(!is_finished)
        Assignment(assignment ++ command_execs, true)
      }
    }
  }

  sealed case class State(
    val versions: Map[Version_ID, Version] = Map(),
    val commands: Map[Command_ID, Command.State] = Map(),
    val execs: Map[Exec_ID, (Command.State, Set[Version])] = Map(),
    val assignments: Map[Version_ID, State.Assignment] = Map(),
    val disposed: Set[ID] = Set(),  // FIXME unused!?
    val history: History = History.init)
  {
    private def fail[A]: A = throw new State.Fail(this)

    def define_version(version: Version, former_assignment: Map[Command, Exec_ID]): State =
    {
      val id = version.id
      if (versions.isDefinedAt(id) || disposed(id)) fail
      copy(versions = versions + (id -> version),
        assignments = assignments + (id -> State.Assignment(former_assignment)))
    }

    def define_command(command: Command): State =
    {
      val id = command.id
      if (commands.isDefinedAt(id) || disposed(id)) fail
      copy(commands = commands + (id -> command.empty_state))
    }

    def lookup_command(id: Command_ID): Option[Command] = commands.get(id).map(_.command)

    def the_version(id: Version_ID): Version = versions.getOrElse(id, fail)
    def the_command(id: Command_ID): Command.State = commands.getOrElse(id, fail)
    def the_exec_state(id: Exec_ID): Command.State = execs.getOrElse(id, fail)._1
    def the_assignment(version: Version): State.Assignment =
      assignments.getOrElse(version.id, fail)

    def accumulate(id: ID, message: XML.Elem): (Command.State, State) =
      execs.get(id) match {
        case Some((st, occs)) =>
          val new_st = st.accumulate(message)
          (new_st, copy(execs = execs + (id -> (new_st, occs))))
        case None =>
          commands.get(id) match {
            case Some(st) =>
              val new_st = st.accumulate(message)
              (new_st, copy(commands = commands + (id -> new_st)))
            case None => fail
          }
      }

    def assign(id: Version_ID, edits: List[(Command_ID, Exec_ID)]): (List[Command], State) =
    {
      val version = the_version(id)
      val occs = Set(version)  // FIXME unused (!?)

      var new_execs = execs
      val assigned_execs =
        for ((cmd_id, exec_id) <- edits) yield {
          val st = the_command(cmd_id)
          if (new_execs.isDefinedAt(exec_id) || disposed(exec_id)) fail
          new_execs += (exec_id -> (st, occs))
          (st.command, exec_id)
        }
      val new_assignment = the_assignment(version).assign(assigned_execs)
      val new_state = copy(assignments = assignments + (id -> new_assignment), execs = new_execs)
      (assigned_execs.map(_._1), new_state)
    }

    def is_assigned(version: Version): Boolean =
      assignments.get(version.id) match {
        case Some(assgn) => assgn.is_finished
        case None => false
      }

    def extend_history(previous: Future[Version],
        edits: List[Edit_Text],
        version: Future[Version]): (Change, State) =
    {
      val change = Change(previous, edits, version)
      (change, copy(history = history + change))
    }


    // persistent user-view
    def snapshot(name: String, pending_edits: List[Text.Edit]): Snapshot =
    {
      val found_stable = history.undo_list.find(change =>
        change.is_finished && is_assigned(change.version.get_finished))
      require(found_stable.isDefined)
      val stable = found_stable.get
      val latest = history.undo_list.head

      // FIXME proper treatment of removed nodes
      val edits =
        (pending_edits /: history.undo_list.takeWhile(_ != stable))((edits, change) =>
            (for ((a, Node.Edits(es)) <- change.edits if a == name) yield es).flatten ::: edits)
      lazy val reverse_edits = edits.reverse

      new Snapshot
      {
        val version = stable.version.get_finished
        val node = version.nodes(name)
        val is_outdated = !(pending_edits.isEmpty && latest == stable)

        def lookup_command(id: Command_ID): Option[Command] = State.this.lookup_command(id)

        def state(command: Command): Command.State =
          try { the_exec_state(the_assignment(version).get_finished.getOrElse(command, fail)) }
          catch { case _: State.Fail => command.empty_state }

        def convert(offset: Text.Offset) = (offset /: edits)((i, edit) => edit.convert(i))
        def revert(offset: Text.Offset) = (offset /: reverse_edits)((i, edit) => edit.revert(i))
        def convert(range: Text.Range) = (range /: edits)((r, edit) => edit.convert(r))
        def revert(range: Text.Range) = (range /: reverse_edits)((r, edit) => edit.revert(r))

        def select_markup[A](range: Text.Range)(result: Markup_Tree.Select[A])
          : Stream[Text.Info[Option[A]]] =
        {
          val former_range = revert(range)
          for {
            (command, command_start) <- node.command_range(former_range).toStream
            Text.Info(r0, x) <- state(command).markup.
              select((former_range - command_start).restrict(command.range)) {
                case Text.Info(r0, info)
                if result.isDefinedAt(Text.Info(convert(r0 + command_start), info)) =>
                  result(Text.Info(convert(r0 + command_start), info))
              }
          } yield Text.Info(convert(r0 + command_start), x)
        }
      }
    }
  }
}

