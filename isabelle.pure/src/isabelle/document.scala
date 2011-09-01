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

  type Edit[A, B] = (Node.Name, Node.Edit[A, B])
  type Edit_Text = Edit[Text.Edit, Text.Perspective]
  type Edit_Command = Edit[(Option[Command], Option[Command]), Command.Perspective]

  type Node_Header = Exn.Result[Thy_Header]

  object Node
  {
    sealed case class Name(node: String, master_dir: String, theory: String)
    {
      override def hashCode: Int = node.hashCode
      override def equals(that: Any): Boolean =
        that match {
          case other: Name => node == other.node
          case _ => false
        }
    }

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

    def norm_header(f: String => String, g: String => String, header: Node_Header): Node_Header =
      header match {
        case Exn.Res(h) => Exn.capture { h.norm_deps(f, g) }
        case exn => exn
      }

    val empty: Node =
      Node(Exn.Exn(ERROR("Bad theory header")), Command.Perspective.empty, Map(), Linear_Set())

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
    val perspective: Command.Perspective,
    val blobs: Map[String, Blob],
    val commands: Linear_Set[Command])
  {
    def clear: Node = Node.empty.copy(header = header)


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

  type Nodes = Map[Node.Name, Node]
  sealed case class Version(val id: Version_ID, val nodes: Nodes)


  /* changes of plain text, eventually resulting in document edits */

  object Change
  {
    val init: Change = Change(Future.value(Version.init), Nil, Future.value(Version.init))
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
    val init: History = new History(List(Change.init))
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
    val state: State
    val version: Version
    val node: Node
    val is_outdated: Boolean
    def command_state(command: Command): Command.State
    def convert(i: Text.Offset): Text.Offset
    def convert(range: Text.Range): Text.Range
    def revert(i: Text.Offset): Text.Offset
    def revert(range: Text.Range): Text.Range
    def select_markup[A](range: Text.Range)(result: Markup_Tree.Select[A])
      : Stream[Text.Info[Option[A]]]
  }

  type Assign =
   (List[(Document.Command_ID, Option[Document.Exec_ID])],  // exec state assignment
    List[(String, Option[Document.Command_ID])])  // last exec

  val no_assign: Assign = (Nil, Nil)

  object State
  {
    class Fail(state: State) extends Exception

    val init: State =
      State().define_version(Version.init, Assignment.init).assign(Version.init.id, no_assign)._2

    object Assignment
    {
      val init: Assignment = Assignment(Map.empty, Map.empty, false)
    }

    case class Assignment(
      val command_execs: Map[Command_ID, Exec_ID],
      val last_execs: Map[String, Option[Command_ID]],
      val is_finished: Boolean)
    {
      def check_finished: Assignment = { require(is_finished); this }
      def unfinished: Assignment = copy(is_finished = false)

      def assign(add_command_execs: List[(Command_ID, Option[Exec_ID])],
        add_last_execs: List[(String, Option[Command_ID])]): Assignment =
      {
        require(!is_finished)
        val command_execs1 =
          (command_execs /: add_command_execs) {
            case (res, (command_id, None)) => res - command_id
            case (res, (command_id, Some(exec_id))) => res + (command_id -> exec_id)
          }
        Assignment(command_execs1, last_execs ++ add_last_execs, true)
      }
    }
  }

  sealed case class State(
    val versions: Map[Version_ID, Version] = Map(),
    val commands: Map[Command_ID, Command.State] = Map(),  // static markup from define_command
    val execs: Map[Exec_ID, Command.State] = Map(),  // dynamic markup from execution
    val assignments: Map[Version_ID, State.Assignment] = Map(),
    val disposed: Set[ID] = Set(),  // FIXME unused!?
    val history: History = History.init)
  {
    private def fail[A]: A = throw new State.Fail(this)

    def define_version(version: Version, assignment: State.Assignment): State =
    {
      val id = version.id
      if (versions.isDefinedAt(id) || disposed(id)) fail
      copy(versions = versions + (id -> version),
        assignments = assignments + (id -> assignment.unfinished))
    }

    def define_command(command: Command): State =
    {
      val id = command.id
      if (commands.isDefinedAt(id) || disposed(id)) fail
      copy(commands = commands + (id -> command.empty_state))
    }

    def defined_command(id: Command_ID): Boolean = commands.isDefinedAt(id)

    def find_command(version: Version, id: ID): Option[(Node, Command)] =
      commands.get(id) orElse execs.get(id) match {
        case None => None
        case Some(st) =>
          val command = st.command
          version.nodes.get(command.node_name).map((_, command))
      }

    def the_version(id: Version_ID): Version = versions.getOrElse(id, fail)
    def the_command(id: Command_ID): Command.State = commands.getOrElse(id, fail)  // FIXME rename
    def the_exec(id: Exec_ID): Command.State = execs.getOrElse(id, fail)
    def the_assignment(version: Version): State.Assignment =
      assignments.getOrElse(version.id, fail)

    def accumulate(id: ID, message: XML.Elem): (Command.State, State) =
      execs.get(id) match {
        case Some(st) =>
          val new_st = st.accumulate(message)
          (new_st, copy(execs = execs + (id -> new_st)))
        case None =>
          commands.get(id) match {
            case Some(st) =>
              val new_st = st.accumulate(message)
              (new_st, copy(commands = commands + (id -> new_st)))
            case None => fail
          }
      }

    def assign(id: Version_ID, arg: Assign): (List[Command], State) =
    {
      val version = the_version(id)
      val (command_execs, last_execs) = arg

      val (changed_commands, new_execs) =
        ((Nil: List[Command], execs) /: command_execs) {
          case ((commands1, execs1), (cmd_id, exec)) =>
            val st = the_command(cmd_id)
            val commands2 = st.command :: commands1
            val execs2 =
              exec match {
                case None => execs1
                case Some(exec_id) => execs1 + (exec_id -> st)
              }
            (commands2, execs2)
        }
      val new_assignment = the_assignment(version).assign(command_execs, last_execs)
      val new_state = copy(assignments = assignments + (id -> new_assignment), execs = new_execs)

      (changed_commands, new_state)
    }

    def is_assigned(version: Version): Boolean =
      assignments.get(version.id) match {
        case Some(assgn) => assgn.is_finished
        case None => false
      }

    def is_stable(change: Change): Boolean =
      change.is_finished && is_assigned(change.version.get_finished)

    def recent_stable: Option[Change] = history.undo_list.find(is_stable)
    def tip_stable: Boolean = is_stable(history.tip)
    def tip_version: Version = history.tip.version.get_finished

    def last_exec_offset(name: Node.Name): Text.Offset =
    {
      val version = tip_version
      the_assignment(version).last_execs.get(name.node) match {
        case Some(Some(id)) =>
          val node = version.nodes(name)
          val cmd = the_command(id).command
          node.command_start(cmd) match {
            case None => 0
            case Some(start) => start + cmd.length
          }
        case _ => 0
      }
    }

    def continue_history(
        previous: Future[Version],
        edits: List[Edit_Text],
        version: Future[Version]): (Change, State) =
    {
      val change = Change(previous, edits, version)
      (change, copy(history = history + change))
    }

    def command_state(version: Version, command: Command): Command.State =
    {
      require(is_assigned(version))
      try {
        the_exec(the_assignment(version).check_finished.command_execs
          .getOrElse(command.id, fail))
      }
      catch { case _: State.Fail => command.empty_state }
    }


    // persistent user-view
    def snapshot(name: Node.Name, pending_edits: List[Text.Edit]): Snapshot =
    {
      val stable = recent_stable.get
      val latest = history.tip

      // FIXME proper treatment of removed nodes
      val edits =
        (pending_edits /: history.undo_list.takeWhile(_ != stable))((edits, change) =>
            (for ((a, Node.Edits(es)) <- change.edits if a == name) yield es).flatten ::: edits)
      lazy val reverse_edits = edits.reverse

      new Snapshot
      {
        val state = State.this
        val version = stable.version.get_finished
        val node = version.nodes(name)
        val is_outdated = !(pending_edits.isEmpty && latest == stable)
        def command_state(command: Command): Command.State = state.command_state(version, command)

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
            Text.Info(r0, x) <- command_state(command).markup.
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

