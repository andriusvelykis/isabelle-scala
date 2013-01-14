/*  Title:      Pure/System/isabelle_system.scala
    Author:     Makarius

Fundamental Isabelle system environment: quasi-static module with
optional init operation.
*/

package isabelle

import java.lang.System
import java.util.regex.Pattern
import java.io.{InputStream, OutputStream, File => JFile, BufferedReader, InputStreamReader,
  BufferedWriter, OutputStreamWriter, IOException, FileInputStream, BufferedInputStream}
import java.awt.{GraphicsEnvironment, Font}
import java.awt.font.TextAttribute
import javax.swing.ImageIcon

import scala.io.Source
import scala.util.matching.Regex


object Isabelle_System
{
  /** bootstrap information **/

  def jdk_home(): String =
  {
    val java_home = System.getProperty("java.home")
    val home = new JFile(java_home)
    val parent = home.getParent
    if (home.getName == "jre" && parent != null &&
        (new JFile(new JFile(parent, "bin"), "javac")).exists) parent
    else java_home
  }

  def cygwin_root(): String =
  {
    require(Platform.is_windows)

    val cygwin_root1 = System.getenv("CYGWIN_ROOT")
    val cygwin_root2 = System.getProperty("cygwin.root")

    if (cygwin_root1 != null && cygwin_root1 != "") cygwin_root1
    else if (cygwin_root2 != null && cygwin_root2 != "") cygwin_root2
    else error("Cannot determine Cygwin root directory")
  }



  /** implicit settings environment **/

  @volatile private var _settings: Option[Map[String, String]] = None

  def settings(): Map[String, String] =
  {
    if (_settings.isEmpty) init()  // unsynchronized check
    _settings.get
  }

  /*
    isabelle_home precedence:
      (1) this_isabelle_home as explicit argument
      (2) ISABELLE_HOME process environment variable (e.g. inherited from running isabelle tool)
      (3) isabelle.home system property (e.g. via JVM application boot process)
  */
  def init(this_isabelle_home: String = null): Unit = synchronized {
    if (_settings.isEmpty) {
      import scala.collection.JavaConversions._

      val settings =
      {
        val env0 = sys.env + ("ISABELLE_JDK_HOME" -> posix_path(jdk_home()))

        val user_home = System.getProperty("user.home")
        val env =
          if (user_home == null || env0.isDefinedAt("HOME")) env0
          else env0 + ("HOME" -> user_home)

        val isabelle_home =
          if (this_isabelle_home != null) this_isabelle_home
          else
            env.get("ISABELLE_HOME") match {
              case None | Some("") =>
                val path = System.getProperty("isabelle.home")
                if (path == null || path == "") error("Unknown Isabelle home directory")
                else path
              case Some(path) => path
            }

          File.with_tmp_file("settings") { dump =>
              val shell_prefix =
                if (Platform.is_windows) List(cygwin_root() + "\\bin\\bash", "-l")
                else Nil
              val cmdline =
                shell_prefix ::: List(isabelle_home + "/bin/isabelle", "getenv", "-d", dump.toString)
              val (output, rc) = process_output(raw_execute(null, env, true, cmdline: _*))
              if (rc != 0) error(output)

              val entries =
                (for (entry <- File.read(dump) split "\0" if entry != "") yield {
                  val i = entry.indexOf('=')
                  if (i <= 0) (entry -> "")
                  else (entry.substring(0, i) -> entry.substring(i + 1))
                }).toMap
              entries + ("PATH" -> entries("PATH_JVM")) - "PATH_JVM"
            }
          }
      _settings = Some(settings)
    }
  }


  /* getenv */

  def getenv(name: String): String = settings.getOrElse(name, "")

  def getenv_strict(name: String): String =
  {
    val value = getenv(name)
    if (value != "") value else error("Undefined environment variable: " + name)
  }


  /** file-system operations **/

  /* jvm_path */

  private val Cygdrive = new Regex("/cygdrive/([a-zA-Z])($|/.*)")
  private val Named_Root = new Regex("//+([^/]*)(.*)")

  def jvm_path(posix_path: String): String =
    if (Platform.is_windows) {
      val result_path = new StringBuilder
      val rest =
        posix_path match {
          case Cygdrive(drive, rest) =>
            result_path ++= (Library.uppercase(drive) + ":" + JFile.separator)
            rest
          case Named_Root(root, rest) =>
            result_path ++= JFile.separator
            result_path ++= JFile.separator
            result_path ++= root
            rest
          case path if path.startsWith("/") =>
            result_path ++= cygwin_root()
            path
          case path => path
        }
      for (p <- space_explode('/', rest) if p != "") {
        val len = result_path.length
        if (len > 0 && result_path(len - 1) != JFile.separatorChar)
          result_path += JFile.separatorChar
        result_path ++= p
      }
      result_path.toString
    }
    else posix_path


  /* posix_path */

  def posix_path(jvm_path: String): String =
    if (Platform.is_windows) {
      val Platform_Root = new Regex("(?i)" +
        Pattern.quote(cygwin_root()) + """(?:\\+|\z)(.*)""")
      val Drive = new Regex("""([a-zA-Z]):\\*(.*)""")

      jvm_path.replace('/', '\\') match {
        case Platform_Root(rest) => "/" + rest.replace('\\', '/')
        case Drive(letter, rest) =>
          "/cygdrive/" + Library.lowercase(letter) +
            (if (rest == "") "" else "/" + rest.replace('\\', '/'))
        case path => path.replace('\\', '/')
      }
    }
    else jvm_path

  def posix_path(file: JFile): String = posix_path(file.getPath)


  /* misc path specifications */

  def standard_path(path: Path): String = path.expand.implode

  def platform_path(path: Path): String = jvm_path(standard_path(path))
  def platform_file(path: Path): JFile = new JFile(platform_path(path))

  def platform_file_url(raw_path: Path): String =
  {
    val path = raw_path.expand
    require(path.is_absolute)
    val s = platform_path(path).replaceAll(" ", "%20")
    if (!Platform.is_windows) "file://" + s
    else if (s.startsWith("\\\\")) "file:" + s.replace('\\', '/')
    else "file:///" + s.replace('\\', '/')
  }


  /* source files of Isabelle/ML bootstrap */

  def source_file(path: Path): Option[Path] =
  {
    def check(p: Path): Option[Path] = if (p.is_file) Some(p) else None

    if (path.is_absolute || path.is_current) check(path)
    else {
      check(Path.explode("~~/src/Pure") + path) orElse
        (if (getenv("ML_SOURCES") == "") None else check(Path.explode("$ML_SOURCES") + path))
    }
  }


  /* mkdirs */

  def mkdirs(path: Path)
  {
    path.file.mkdirs
    if (!path.is_dir) error("Cannot create directory: " + quote(platform_path(path)))
  }



  /** external processes **/

  /* raw execute for bootstrapping */

  private def raw_execute(cwd: JFile, env: Map[String, String], redirect: Boolean, args: String*)
    : Process =
  {
    val cmdline = new java.util.LinkedList[String]
    for (s <- args) cmdline.add(s)

    val proc = new ProcessBuilder(cmdline)
    if (cwd != null) proc.directory(cwd)
    if (env != null) {
      proc.environment.clear
      for ((x, y) <- env) proc.environment.put(x, y)
    }
    proc.redirectErrorStream(redirect)
    proc.start
  }

  private def process_output(proc: Process): (String, Int) =
  {
    proc.getOutputStream.close
    val output = File.read_stream(proc.getInputStream)
    val rc =
      try { proc.waitFor }
      finally {
        proc.getInputStream.close
        proc.getErrorStream.close
        proc.destroy
        Thread.interrupted
      }
    (output, rc)
  }


  /* plain execute */

  def execute_env(cwd: JFile, env: Map[String, String], redirect: Boolean, args: String*): Process =
  {
    val cmdline =
      if (Platform.is_windows) List(cygwin_root() + "\\bin\\env.exe") ++ args
      else args
    val env1 = if (env == null) settings else settings ++ env
    raw_execute(cwd, env1, redirect, cmdline: _*)
  }

  def execute(redirect: Boolean, args: String*): Process =
    execute_env(null, null, redirect, args: _*)


  /* managed process */

  class Managed_Process(cwd: JFile, env: Map[String, String], redirect: Boolean, args: String*)
  {
    private val params =
      List(standard_path(Path.explode("~~/lib/scripts/process")), "group", "-", "no_script")
    private val proc = execute_env(cwd, env, redirect, (params ++ args):_*)


    // channels

    val stdin: BufferedWriter =
      new BufferedWriter(new OutputStreamWriter(proc.getOutputStream, UTF8.charset))

    val stdout: BufferedReader =
      new BufferedReader(new InputStreamReader(proc.getInputStream, UTF8.charset))

    val stderr: BufferedReader =
      new BufferedReader(new InputStreamReader(proc.getErrorStream, UTF8.charset))


    // signals

    private val pid = stdout.readLine

    private def kill(signal: String): Boolean =
    {
      execute(true, "kill", "-" + signal, "-" + pid).waitFor
      execute(true, "kill", "-0", "-" + pid).waitFor == 0
    }

    private def multi_kill(signal: String): Boolean =
    {
      var running = true
      var count = 10
      while (running && count > 0) {
        if (kill(signal)) {
          Thread.sleep(100)
          count -= 1
        }
        else running = false
      }
      running
    }

    def interrupt() { multi_kill("INT") }
    def terminate() { multi_kill("INT") && multi_kill("TERM") && kill("KILL"); proc.destroy }


    // JVM shutdown hook

    private val shutdown_hook = new Thread { override def run = terminate() }

    try { Runtime.getRuntime.addShutdownHook(shutdown_hook) }
    catch { case _: IllegalStateException => }

    private def cleanup() =
      try { Runtime.getRuntime.removeShutdownHook(shutdown_hook) }
      catch { case _: IllegalStateException => }


    /* result */

    def join: Int = { val rc = proc.waitFor; cleanup(); rc }
  }


  /* bash */

  final case class Bash_Result(out_lines: List[String], err_lines: List[String], rc: Int)
  {
    def out: String = cat_lines(out_lines)
    def err: String = cat_lines(err_lines)
    def add_err(s: String): Bash_Result = copy(err_lines = err_lines ::: List(s))
  }

  def bash_env(cwd: JFile, env: Map[String, String], script: String,
    out_progress: String => Unit = (_: String) => (),
    err_progress: String => Unit = (_: String) => ()): Bash_Result =
  {
    File.with_tmp_file("isabelle_script") { script_file =>
      File.write(script_file, script)
      val proc = new Managed_Process(cwd, env, false, "bash", posix_path(script_file))

      proc.stdin.close
      val (_, stdout) =
        Simple_Thread.future("bash_stdout") { File.read_lines(proc.stdout, out_progress) }
      val (_, stderr) =
        Simple_Thread.future("bash_stderr") { File.read_lines(proc.stderr, err_progress) }

      val rc =
        try { proc.join }
        catch { case e: InterruptedException => Thread.interrupted; proc.terminate; 130 }
      Bash_Result(stdout.join, stderr.join, rc)
    }
  }

  def bash(script: String): Bash_Result = bash_env(null, null, script)


  /* system tools */

  def isabelle_tool(name: String, args: String*): (String, Int) =
  {
    Path.split(getenv_strict("ISABELLE_TOOLS")).find { dir =>
      val file = (dir + Path.basic(name)).file
      try {
        file.isFile && file.canRead && file.canExecute &&
          !name.endsWith("~") && !name.endsWith(".orig")
      }
      catch { case _: SecurityException => false }
    } match {
      case Some(dir) =>
        val file = standard_path(dir + Path.basic(name))
        process_output(execute(true, (List(file) ++ args): _*))
      case None => ("Unknown Isabelle tool: " + name, 2)
    }
  }



  /** Isabelle resources **/

  /* components */

  def components(): List[Path] =
    Path.split(getenv_strict("ISABELLE_COMPONENTS"))


  /* logic images */

  def find_logics_dirs(): List[Path] =
  {
    val ml_ident = Path.explode("$ML_IDENTIFIER").expand
    Path.split(getenv_strict("ISABELLE_PATH")).map(_ + ml_ident)
  }

  def find_logics(): List[String] =
    (for {
      dir <- find_logics_dirs()
      files = dir.file.listFiles() if files != null
      file <- files.toList if file.isFile } yield file.getName).sorted

  def default_logic(args: String*): String =
  {
    args.find(_ != "") match {
      case Some(logic) => logic
      case None => Isabelle_System.getenv_strict("ISABELLE_LOGIC")
    }
  }


  /* fonts */

  def get_font(family: String = "IsabelleText", size: Int = 1, bold: Boolean = false): Font =
    new Font(family, if (bold) Font.BOLD else Font.PLAIN, size)

  def install_fonts()
  {
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    for (font <- Path.split(getenv_strict("ISABELLE_FONTS")))
      ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, font.file))
  }

  def install_fonts_jfx()
  {
    for (font <- Path.split(getenv_strict("ISABELLE_FONTS"))) {
      val stream = new BufferedInputStream(new FileInputStream(font.file))
      try { javafx.scene.text.Font.loadFont(stream, 1.0) }
      finally { stream.close }
    }
  }


  /* icon */

  def get_icon(): ImageIcon =
    new ImageIcon(platform_path(Path.explode("~~/lib/logo/isabelle.gif")))
}
