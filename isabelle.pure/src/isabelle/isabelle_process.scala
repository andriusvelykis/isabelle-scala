/*  Title:      Pure/System/isabelle_process.scala
    Author:     Makarius
    Options:    :folding=explicit:collapseFolds=1:

Isabelle process management -- always reactive due to multi-threaded I/O.
*/

package isabelle

import java.lang.System
import java.util.concurrent.LinkedBlockingQueue
import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter,
  InputStream, OutputStream, BufferedOutputStream, IOException}

import scala.actors.Actor
import Actor._


object Isabelle_Process
{
  /* messages */

  sealed abstract class Message

  class Input(name: String, args: List[String]) extends Message
  {
    override def toString: String =
      XML.Elem(Markup(Markup.PROVER_COMMAND, List((Markup.NAME, name))),
        args.map(s =>
          List(XML.Text("\n"), XML.elem(Markup.PROVER_ARG, YXML.parse_body(s)))).flatten).toString
  }

  class Output(val message: XML.Elem) extends Message
  {
    def kind: String = message.markup.name
    def properties: Properties.T = message.markup.properties
    def body: XML.Body = message.body

    def is_init = kind == Markup.INIT
    def is_exit = kind == Markup.EXIT
    def is_stdout = kind == Markup.STDOUT
    def is_stderr = kind == Markup.STDERR
    def is_system = kind == Markup.SYSTEM
    def is_status = kind == Markup.STATUS
    def is_report = kind == Markup.REPORT
    def is_protocol = kind == Markup.PROTOCOL
    def is_syslog = is_init || is_exit || is_system || is_stderr

    override def toString: String =
    {
      val res =
        if (is_status || is_report) message.body.map(_.toString).mkString
        else if (is_protocol) "..."
        else Pretty.string_of(message.body)
      if (properties.isEmpty)
        kind.toString + " [[" + res + "]]"
      else
        kind.toString + " " +
          (for ((x, y) <- properties) yield x + "=" + y).mkString("{", ",", "}") + " [[" + res + "]]"
    }
  }
}


class Isabelle_Process(
    receiver: Isabelle_Process.Message => Unit = Console.println(_),
    args: List[String] = Nil)
{
  import Isabelle_Process._


  /* text representation */

  def encode(s: String): String = Symbol.encode(s)
  def decode(s: String): String = Symbol.decode(s)

  object Encode
  {
    val string: XML.Encode.T[String] = (s => XML.Encode.string(encode(s)))
  }


  /* output */

  private def system_output(text: String)
  {
    receiver(new Output(XML.Elem(Markup(Markup.SYSTEM, Nil), List(XML.Text(text)))))
  }

  private val xml_cache = new XML.Cache()

  private def output_message(kind: String, props: Properties.T, body: XML.Body)
  {
    if (kind == Markup.INIT) system_channel.accepted()
    if (kind == Markup.PROTOCOL)
      receiver(new Output(XML.Elem(Markup(kind, props), body)))
    else {
      val main = XML.Elem(Markup(kind, props), Protocol.clean_message(body))
      val reports = Protocol.message_reports(props, body)
      for (msg <- main :: reports)
        receiver(new Output(xml_cache.cache_tree(msg).asInstanceOf[XML.Elem]))
    }
  }

  private def exit_message(rc: Int)
  {
    output_message(Markup.EXIT, Markup.Return_Code(rc),
      List(XML.Text("Return code: " + rc.toString)))
  }


  /* input actors */

  private case class Input_Text(text: String)
  private case class Input_Chunks(chunks: List[Array[Byte]])

  private case object Close
  private def close(p: (Thread, Actor))
  {
    if (p != null && p._1.isAlive) {
      p._2 ! Close
      p._1.join
    }
  }

  @volatile private var standard_input: (Thread, Actor) = null
  @volatile private var command_input: (Thread, Actor) = null


  /** process manager **/

  private val system_channel = System_Channel()

  private val process =
    try {
      val cmdline =
        Isabelle_System.getenv_strict("ISABELLE_PROCESS") ::
          (system_channel.isabelle_args ::: args)
      new Isabelle_System.Managed_Process(null, null, false, cmdline: _*)
    }
    catch { case e: IOException => system_channel.accepted(); throw(e) }

  val (_, process_result) =
    Simple_Thread.future("process_result") { process.join }

  private def terminate_process()
  {
    try { process.terminate }
    catch { case e: IOException => system_output("Failed to terminate Isabelle: " + e.getMessage) }
  }

  private val process_manager = Simple_Thread.fork("process_manager")
  {
    val (startup_failed, startup_errors) =
    {
      var finished: Option[Boolean] = None
      val result = new StringBuilder(100)
      while (finished.isEmpty && (process.stderr.ready || !process_result.is_finished)) {
        while (finished.isEmpty && process.stderr.ready) {
          try {
            val c = process.stderr.read
            if (c == 2) finished = Some(true)
            else result += c.toChar
          }
          catch { case _: IOException => finished = Some(false) }
        }
        Thread.sleep(10)
      }
      (finished.isEmpty || !finished.get, result.toString.trim)
    }
    if (startup_errors != "") system_output(startup_errors)

    if (startup_failed) {
      exit_message(127)
      process.stdin.close
      Thread.sleep(300)
      terminate_process()
      process_result.join
    }
    else {
      val (command_stream, message_stream) = system_channel.rendezvous()

      standard_input = stdin_actor()
      val stdout = physical_output_actor(false)
      val stderr = physical_output_actor(true)
      command_input = input_actor(command_stream)
      val message = message_actor(message_stream)

      val rc = process_result.join
      system_output("process terminated")
      close_input()
      for ((thread, _) <- List(standard_input, stdout, stderr, command_input, message))
        thread.join
      system_output("process_manager terminated")
      exit_message(rc)
    }
    system_channel.accepted()
  }


  /* management methods */

  def join() { process_manager.join() }

  def terminate()
  {
    close_input()
    system_output("Terminating Isabelle process")
    terminate_process()
  }



  /** stream actors **/

  /* physical stdin */

  private def stdin_actor(): (Thread, Actor) =
  {
    val name = "standard_input"
    Simple_Thread.actor(name) {
      try {
        var finished = false
        while (!finished) {
          //{{{
          receive {
            case Input_Text(text) =>
              process.stdin.write(text)
              process.stdin.flush
            case Close =>
              process.stdin.close
              finished = true
            case bad => System.err.println(name + ": ignoring bad message " + bad)
          }
          //}}}
        }
      }
      catch { case e: IOException => system_output(name + ": " + e.getMessage) }
      system_output(name + " terminated")
    }
  }


  /* physical output */

  private def physical_output_actor(err: Boolean): (Thread, Actor) =
  {
    val (name, reader, markup) =
      if (err) ("standard_error", process.stderr, Markup.STDERR)
      else ("standard_output", process.stdout, Markup.STDOUT)

    Simple_Thread.actor(name) {
      try {
        var result = new StringBuilder(100)
        var finished = false
        while (!finished) {
          //{{{
          var c = -1
          var done = false
          while (!done && (result.length == 0 || reader.ready)) {
            c = reader.read
            if (c >= 0) result.append(c.asInstanceOf[Char])
            else done = true
          }
          if (result.length > 0) {
            output_message(markup, Nil, List(XML.Text(decode(result.toString))))
            result.length = 0
          }
          else {
            reader.close
            finished = true
          }
          //}}}
        }
      }
      catch { case e: IOException => system_output(name + ": " + e.getMessage) }
      system_output(name + " terminated")
    }
  }


  /* command input */

  private def input_actor(raw_stream: OutputStream): (Thread, Actor) =
  {
    val name = "command_input"
    Simple_Thread.actor(name) {
      try {
        val stream = new BufferedOutputStream(raw_stream)
        var finished = false
        while (!finished) {
          //{{{
          receive {
            case Input_Chunks(chunks) =>
              stream.write(UTF8.string_bytes(chunks.map(_.length).mkString("", ",", "\n")))
              chunks.foreach(stream.write(_))
              stream.flush
            case Close =>
              stream.close
              finished = true
            case bad => System.err.println(name + ": ignoring bad message " + bad)
          }
          //}}}
        }
      }
      catch { case e: IOException => system_output(name + ": " + e.getMessage) }
      system_output(name + " terminated")
    }
  }


  /* message output */

  private def message_actor(stream: InputStream): (Thread, Actor) =
  {
    class EOF extends Exception
    class Protocol_Error(msg: String) extends Exception(msg)

    val name = "message_output"
    Simple_Thread.actor(name) {
      val default_buffer = new Array[Byte](65536)
      var c = -1

      def read_chunk(do_decode: Boolean): XML.Body =
      {
        //{{{
        // chunk size
        var n = 0
        c = stream.read
        if (c == -1) throw new EOF
        while (48 <= c && c <= 57) {
          n = 10 * n + (c - 48)
          c = stream.read
        }
        if (c != 10) throw new Protocol_Error("bad message chunk header")

        // chunk content
        val buf =
          if (n <= default_buffer.size) default_buffer
          else new Array[Byte](n)

        var i = 0
        var m = 0
        do {
          m = stream.read(buf, i, n - i)
          if (m != -1) i += m
        } while (m != -1 && n > i)

        if (i != n) throw new Protocol_Error("bad message chunk content")

        if (do_decode)
          YXML.parse_body_failsafe(UTF8.decode_chars(decode, buf, 0, n))
        else List(XML.Text(UTF8.decode_chars(s => s, buf, 0, n).toString))
        //}}}
      }

      try {
        do {
          try {
            //{{{
            val header = read_chunk(true)
            header match {
              case List(XML.Elem(Markup(name, props), Nil)) =>
                val kind = name.intern
                val body = read_chunk(kind != Markup.PROTOCOL)
                output_message(kind, props, body)
              case _ =>
                read_chunk(false)
                throw new Protocol_Error("bad header: " + header.toString)
            }
            //}}}
          }
          catch { case _: EOF => }
        } while (c != -1)
      }
      catch {
        case e: IOException => system_output("Cannot read message:\n" + e.getMessage)
        case e: Protocol_Error => system_output("Malformed message:\n" + e.getMessage)
      }
      stream.close

      system_output(name + " terminated")
    }
  }


  /** main methods **/

  def input_raw(text: String): Unit = standard_input._2 ! Input_Text(text)

  def input_bytes(name: String, args: Array[Byte]*): Unit =
    command_input._2 ! Input_Chunks(UTF8.string_bytes(name) :: args.toList)

  def input(name: String, args: String*)
  {
    receiver(new Input(name, args.toList))
    input_bytes(name, args.map(UTF8.string_bytes): _*)
  }

  def options(opts: Options): Unit =
    input("Isabelle_Process.options", YXML.string_of_body(opts.encode))

  def close_input()
  {
    close(command_input)
    close(standard_input)
  }
}
