/*  Title:      Pure/PIDE/markup.scala
    Module:     PIDE
    Author:     Makarius

Isabelle-specific implementation of quasi-abstract markup elements.
*/

package isabelle


object Markup
{
  /* properties */

  val NAME = "name"
  val Name = new Properties.String(NAME)

  val KIND = "kind"
  val Kind = new Properties.String(KIND)


  /* basic markup */

  val Empty = Markup("", Nil)
  val Broken = Markup("broken", Nil)


  /* formal entities */

  val BINDING = "binding"
  val ENTITY = "entity"
  val DEF = "def"
  val REF = "ref"

  object Entity
  {
    def unapply(markup: Markup): Option[(String, String)] =
      markup match {
        case Markup(ENTITY, props @ Kind(kind)) =>
          props match {
            case Name(name) => Some(kind, name)
            case _ => None
          }
        case _ => None
      }
  }


  /* position */

  val LINE = "line"
  val OFFSET = "offset"
  val END_OFFSET = "end_offset"
  val FILE = "file"
  val ID = "id"

  val DEF_LINE = "def_line"
  val DEF_OFFSET = "def_offset"
  val DEF_END_OFFSET = "def_end_offset"
  val DEF_FILE = "def_file"
  val DEF_ID = "def_id"

  val POSITION_PROPERTIES = Set(LINE, OFFSET, END_OFFSET, FILE, ID)
  val POSITION = "position"


  /* path */

  val PATH = "path"

  object Path
  {
    def unapply(markup: Markup): Option[String] =
      markup match {
        case Markup(PATH, Name(name)) => Some(name)
        case _ => None
      }
  }


  /* pretty printing */

  val Indent = new Properties.Int("indent")
  val BLOCK = "block"
  val Width = new Properties.Int("width")
  val BREAK = "break"

  val SEPARATOR = "separator"


  /* hidden text */

  val HIDDEN = "hidden"


  /* logical entities */

  val CLASS = "class"
  val TYPE_NAME = "type_name"
  val FIXED = "fixed"
  val CONSTANT = "constant"

  val DYNAMIC_FACT = "dynamic_fact"


  /* inner syntax */

  val TFREE = "tfree"
  val TVAR = "tvar"
  val FREE = "free"
  val SKOLEM = "skolem"
  val BOUND = "bound"
  val VAR = "var"
  val NUMERAL = "numeral"
  val LITERAL = "literal"
  val DELIMITER = "delimiter"
  val INNER_STRING = "inner_string"
  val INNER_COMMENT = "inner_comment"

  val TOKEN_RANGE = "token_range"

  val SORT = "sort"
  val TYP = "typ"
  val TERM = "term"
  val PROP = "prop"

  val SORTING = "sorting"
  val TYPING = "typing"

  val ATTRIBUTE = "attribute"
  val METHOD = "method"


  /* embedded source text */

  val ML_SOURCE = "ML_source"
  val DOCUMENT_SOURCE = "document_source"

  val ANTIQ = "antiq"
  val ML_ANTIQUOTATION = "ML_antiquotation"
  val DOCUMENT_ANTIQUOTATION = "document_antiquotation"
  val DOCUMENT_ANTIQUOTATION_OPTION = "document_antiquotation_option"


  /* text structure */

  val PARAGRAPH = "paragraph"
  val TEXT_FOLD = "text_fold"


  /* ML syntax */

  val ML_KEYWORD = "ML_keyword"
  val ML_DELIMITER = "ML_delimiter"
  val ML_TVAR = "ML_tvar"
  val ML_NUMERAL = "ML_numeral"
  val ML_CHAR = "ML_char"
  val ML_STRING = "ML_string"
  val ML_COMMENT = "ML_comment"

  val ML_DEF = "ML_def"
  val ML_OPEN = "ML_open"
  val ML_STRUCT = "ML_struct"
  val ML_TYPING = "ML_typing"


  /* outer syntax */

  val KEYWORD = "keyword"
  val OPERATOR = "operator"
  val COMMAND = "command"
  val STRING = "string"
  val ALTSTRING = "altstring"
  val VERBATIM = "verbatim"
  val COMMENT = "comment"
  val CONTROL = "control"

  val KEYWORD1 = "keyword1"
  val KEYWORD2 = "keyword2"


  /* timing */

  val Elapsed = new Properties.Double("elapsed")
  val CPU = new Properties.Double("cpu")
  val GC = new Properties.Double("gc")

  object Timing_Properties
  {
    def apply(timing: isabelle.Timing): Properties.T =
      Elapsed(timing.elapsed.seconds) ::: CPU(timing.cpu.seconds) ::: GC(timing.gc.seconds)
    def unapply(props: Properties.T): Option[isabelle.Timing] =
      (props, props, props) match {
        case (Elapsed(elapsed), CPU(cpu), GC(gc)) =>
          Some(new isabelle.Timing(Time.seconds(elapsed), Time.seconds(cpu), Time.seconds(gc)))
        case _ => None
      }
  }

  val TIMING = "timing"

  object Timing
  {
    def apply(timing: isabelle.Timing): Markup = Markup(TIMING, Timing_Properties(timing))
    def unapply(markup: Markup): Option[isabelle.Timing] =
      markup match {
        case Markup(TIMING, Timing_Properties(timing)) => Some(timing)
        case _ => None
      }
  }


  /* toplevel */

  val SUBGOALS = "subgoals"
  val PROOF_STATE = "proof_state"

  val STATE = "state"
  val GOAL = "goal"
  val SUBGOAL = "subgoal"


  /* command status */

  val TASK = "task"

  val ACCEPTED = "accepted"
  val FORKED = "forked"
  val JOINED = "joined"
  val RUNNING = "running"
  val FINISHED = "finished"
  val FAILED = "failed"


  /* interactive documents */

  val VERSION = "version"
  val ASSIGN = "assign"


  /* prover process */

  val PROVER_COMMAND = "prover_command"
  val PROVER_ARG = "prover_arg"


  /* messages */

  val Serial = new Properties.Long("serial")

  val MESSAGE = "message"

  val INIT = "init"
  val STATUS = "status"
  val REPORT = "report"
  val RESULT = "result"
  val WRITELN = "writeln"
  val TRACING = "tracing"
  val WARNING = "warning"
  val ERROR = "error"
  val PROTOCOL = "protocol"
  val SYSTEM = "system"
  val STDOUT = "stdout"
  val STDERR = "stderr"
  val EXIT = "exit"

  val WRITELN_MESSAGE = "writeln_message"
  val TRACING_MESSAGE = "tracing_message"
  val WARNING_MESSAGE = "warning_message"
  val ERROR_MESSAGE = "error_message"

  val message: String => String =
    Map(WRITELN -> WRITELN_MESSAGE, TRACING -> TRACING_MESSAGE,
        WARNING -> WARNING_MESSAGE, ERROR -> ERROR_MESSAGE).withDefault((s: String) => s)

  val Return_Code = new Properties.Int("return_code")

  val LEGACY = "legacy"

  val NO_REPORT = "no_report"

  val BAD = "bad"

  val INTENSIFY = "intensify"


  /* active areas */

  val BROWSER = "browser"
  val GRAPHVIEW = "graphview"

  val SENDBACK = "sendback"
  val PADDING = "padding"
  val PADDING_LINE = (PADDING, LINE)

  val DIALOG = "dialog"
  val Result = new Properties.String(RESULT)


  /* protocol message functions */

  val FUNCTION = "function"
  val Function = new Properties.String(FUNCTION)

  val Assign_Execs: Properties.T = List((FUNCTION, "assign_execs"))
  val Removed_Versions: Properties.T = List((FUNCTION, "removed_versions"))

  object Invoke_Scala
  {
    def unapply(props: Properties.T): Option[(String, String)] =
      props match {
        case List((FUNCTION, "invoke_scala"), (NAME, name), (ID, id)) => Some((name, id))
        case _ => None
      }
  }
  object Cancel_Scala
  {
    def unapply(props: Properties.T): Option[String] =
      props match {
        case List((FUNCTION, "cancel_scala"), (ID, id)) => Some(id)
        case _ => None
      }
  }

  object ML_Statistics
  {
    def unapply(props: Properties.T): Option[Properties.T] =
      props match {
        case (FUNCTION, "ML_statistics") :: stats => Some(stats)
        case _ => None
      }
  }

  object Task_Statistics
  {
    def unapply(props: Properties.T): Option[Properties.T] =
      props match {
        case (FUNCTION, "task_statistics") :: stats => Some(stats)
        case _ => None
      }
  }
}


sealed case class Markup(name: String, properties: Properties.T)

