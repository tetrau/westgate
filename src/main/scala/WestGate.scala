import java.net.InetSocketAddress
import java.io.File
import scala.concurrent._
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration.FiniteDuration
import akka.stream.scaladsl._
import akka.actor.ActorSystem
import akka.util.ByteString
import akka.stream.scaladsl.Tcp._
import com.typesafe.config.ConfigFactory

object Setting {
  def fromFile(configFile: File): Setting = {
    new Setting(scala.io.Source.fromFile(configFile).getLines.mkString)
  }

  def fromResource(resource: String): Setting = {
    new Setting(scala.io.Source.fromResource(resource).getLines.mkString)
  }
}

class Setting(configString: String) {

  import scala.jdk.CollectionConverters._

  case class RoutingRule(headerField: String, headerValue: String, forwardTo: InetSocketAddress)

  class RoutingTable(val rules: Seq[RoutingRule]) {
    def route(headers: Seq[(String, String)]): InetSocketAddress = {
      headers.flatMap(h => {
        val headerField = h._1
        val headerValue = h._2
        rules.find(r => r.headerField.toLowerCase == headerField.toLowerCase && r.headerValue == headerValue)
      }) match {
        case a :: _ => a.forwardTo
        case Nil => default
      }
    }
  }

  private val config = ConfigFactory.parseString(configString)
  val bind = new InetSocketAddress(config.getString("bind.host"), config.getInt("bind.port"))
  val default = new InetSocketAddress(config.getString("default.host"), config.getInt("default.port"))
  val timeout: Long = if (config.hasPath("timeout")) config.getLong("timeout") else 15L
  val forward: RoutingTable = new RoutingTable(config.getConfigList("forward").asScala.map(
    c => RoutingRule(c.getString("headerField"), c.getString("headerValue"),
      new InetSocketAddress(c.getString("host"), c.getInt("port")))
  ).toList)
}

class WestGate(setting: Setting) {
  val injectTimeoutFlow: Flow[ByteString, Either[ByteString, TimeoutException], akka.NotUsed] =
    Flow[ByteString].map[Either[ByteString, TimeoutException]](Left(_))
      .keepAlive(FiniteDuration(setting.timeout, "s"), () => Right(new TimeoutException("HTTP parse timeout")))

  val httpParseFlow: Flow[Either[ByteString, TimeoutException], HttpParser, akka.NotUsed] =
    Flow[Either[ByteString, TimeoutException]]
      .scan[HttpParser](HttpParser.initState)((s, input) => {
      input match {
        case Right(_) => s match {
          case _: HttpParsingStop => Passthrough(ByteString.empty)
          case ParsingRequest(parsed) => Invalid(parsed)
          case ParsingHeader(_, _, _, parsed) => Invalid(parsed)
        }
        case Left(b) => s.input(b)
      }
    }).drop(1)

  val httpParserResultFlow: Flow[HttpParser, HttpParsingStop, akka.NotUsed] =
    Flow[HttpParser].mapConcat[HttpParsingStop]({
      case s: HttpParsingStop => List(s)
      case _ => List.empty
    })
  val httpParseResultDecodeFlow: Flow[HttpParsingStop, ByteString, akka.NotUsed] =
    Flow[HttpParsingStop].map(_.data)

  def run(): Unit = {
    implicit val system: akka.actor.ActorSystem = ActorSystem("WestGate")
    implicit val ec: ExecutionContext = system.dispatcher
    val connections: Source[IncomingConnection, Future[ServerBinding]] =
      Tcp().bind(setting.bind.getHostString, setting.bind.getPort)
    connections.runForeach { connection =>
      println(s"New connection from: ${connection.remoteAddress}")
      val forwardFlow = Flow[HttpParsingStop].prefixAndTail(1).flatMapConcat({ case (first, rest) =>
        val parseResult = first(0)
        val forwardTarget = parseResult match {
          case r: Finished =>
            setting.forward.route(r.header)
          case _ => setting.default
        }
        println(s"Connection from ${connection.remoteAddress} forward to $forwardTarget")

        def forwardConnection =
          Try(Tcp().outgoingConnection(forwardTarget.getHostName, forwardTarget.getPort)) match {
            case Success(x) => x
            case Failure(exception) =>
              println(s"[${connection.remoteAddress}]: $exception")
              Flow[ByteString].take(0)
          }

        Source(first).concat(rest).via(httpParseResultDecodeFlow).via(forwardConnection)
      })
      connection.handleWith(injectTimeoutFlow.via(httpParseFlow).via(httpParserResultFlow).via(forwardFlow))
    } onComplete (cr => system.terminate().onComplete(_ => cr match {
      case Failure(_) => sys.exit(1)
      case _ => sys.exit(0)
    }))
  }
}

object WestGate {
  def main(args: Array[String]): Unit = {
    val setting = Setting.fromFile(new File(args(0)))
    val westgate = new WestGate(setting)
    westgate.run()
  }
}