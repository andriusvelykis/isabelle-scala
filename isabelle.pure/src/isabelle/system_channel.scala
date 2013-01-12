/*  Title:      Pure/System/system_channel.scala
    Author:     Makarius

Portable system channel for inter-process communication, based on
named pipes or sockets.
*/

package isabelle


import java.io.{InputStream, OutputStream, File => JFile, FileInputStream,
  FileOutputStream, IOException}
import java.net.{ServerSocket, InetAddress}


object System_Channel
{
  def apply(use_socket: Boolean = false): System_Channel =
    if (Platform.is_windows) new Socket_Channel else new Fifo_Channel
}

abstract class System_Channel
{
  def isabelle_args: List[String]
  def rendezvous(): (OutputStream, InputStream)
  def accepted(): Unit
}


/** named pipes **/

private object Fifo_Channel
{
  private val next_fifo = Counter()
}

private class Fifo_Channel extends System_Channel
{
  require(!Platform.is_windows)

  private def mk_fifo(): String =
  {
    val i = Fifo_Channel.next_fifo()
    val script =
      "FIFO=\"/tmp/isabelle-fifo-${PPID}-$$" + i + "\"\n" +
      "echo -n \"$FIFO\"\n" +
      "mkfifo -m 600 \"$FIFO\"\n"
    val result = Isabelle_System.bash(script)
    if (result.rc == 0) result.out else error(result.err)
  }

  private def rm_fifo(fifo: String): Boolean = (new JFile(fifo)).delete

  private def fifo_input_stream(fifo: String): InputStream = new FileInputStream(fifo)
  private def fifo_output_stream(fifo: String): OutputStream = new FileOutputStream(fifo)

  private val fifo1 = mk_fifo()
  private val fifo2 = mk_fifo()

  val isabelle_args: List[String] = List ("-W", fifo1 + ":" + fifo2)

  def rendezvous(): (OutputStream, InputStream) =
  {
    val output_stream = fifo_output_stream(fifo1)
    val input_stream = fifo_input_stream(fifo2)
    (output_stream, input_stream)
  }

  def accepted() { rm_fifo(fifo1); rm_fifo(fifo2) }
}


/** sockets **/

private class Socket_Channel extends System_Channel
{
  private val server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))

  def isabelle_args: List[String] = List("-T", "127.0.0.1:" + server.getLocalPort)

  def rendezvous(): (OutputStream, InputStream) =
  {
    val socket = server.accept
    (socket.getOutputStream, socket.getInputStream)
  }

  def accepted() { server.close }
}
