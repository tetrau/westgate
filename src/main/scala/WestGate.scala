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


class Setting(val configPath: String) {

  import scala.jdk.CollectionConverters._

  private val config = ConfigFactory.parseFile(new File(configPath))

  case class RoutingRule(headerField: String, headerValue: String, forwardTo: InetSocketAddress)

  val bind = new InetSocketAddress(config.getString("bind.host"), config.getInt("bind.port"))
  val default = new InetSocketAddress(config.getString("default.host"), config.getInt("default.port"))
  val timeout: Long = if (config.hasPath("timeout")) config.getLong("timeout") else 15L

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

  println()
  val forward: RoutingTable = new RoutingTable(config.getConfigList("forward").asScala.map(
    c => RoutingRule(c.getString("headerField"), c.getString("headerValue"),
      new InetSocketAddress(c.getString("host"), c.getInt("port")))
  ).toList)
}

object WestGate extends App {
  val setting = new Setting(args(0))
  implicit val system: akka.actor.ActorSystem = ActorSystem("WestGate")
  implicit val ec: ExecutionContext = system.dispatcher
  val httpParseFlow = Flow[ByteString]
    .scan[HttpParser](HttpParser.initState)((s, b) => s.input(b))
  val httpParseTimeoutFlow = Flow[HttpParser].map(Left(_))
    .keepAlive(FiniteDuration(setting.timeout, "s"), () => Right(new TimeoutException("HTTP parse timeout")))
    .scan[Either[HttpParser, TimeoutException]](Left(HttpParser.initState))(
    (wrappedState, nextWrappedState) => nextWrappedState match {
      case x@Left(_) => x
      case Right(_) =>
        wrappedState match {
          case s@Left(_: HttpParsingStop) => s
          case Left(state) =>
            state match {
              case ParsingRequest(parsed) => Left(Invalid(parsed))
              case ParsingHeader(_, _, _, parsed) => Left(Invalid(parsed))
            }
        }
    }).mapConcat[HttpParser]({
    case Left(s) => List(s)
    case _ => List.empty
  })
  val httpParserResultFlow = Flow[HttpParser].mapConcat[HttpParsingStop]({
    case s: HttpParsingStop => List(s)
    case _ => List.empty
  })
  val httpParseResultDecodeFlow = Flow[HttpParsingStop].map(_.data)
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
    connection.handleWith(httpParseFlow.via(httpParseTimeoutFlow).via(httpParserResultFlow).via(forwardFlow))
  } onComplete (cr => system.terminate().onComplete(_ => cr match {
    case Failure(_) => sys.exit(1)
    case _ => sys.exit(0)
  }))
}
