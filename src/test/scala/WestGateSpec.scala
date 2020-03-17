import akka.actor.ActorSystem
import org.scalacheck._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.scalacheck.Prop.{forAllNoShrink, propBoolean}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, TimeoutException}

object WestGateSpec extends Properties("WestGate") {
  override def overrideParameters(p: Test.Parameters): Test.Parameters = p.withMinSuccessfulTests(1000)

  implicit val system: akka.actor.ActorSystem = ActorSystem("WestGate")
  implicit val ec: ExecutionContext = system.dispatcher

  val westgate = new WestGate(Setting.fromResource("test.json"))

  val timeoutGen: Gen[Either[ByteString, TimeoutException]] =
    Gen.const(Right(new TimeoutException("HTTP parse timeout")))
  val timeoutClusterGen: Gen[List[Either[ByteString, TimeoutException]]] = for {
    n <- Gen.choose(0, 2)
    lt <- Gen.listOfN(n, timeoutGen)
  } yield lt
  val httpDataWithTimeoutGen: Gen[List[Either[ByteString, TimeoutException]]] =
    for {
      http: HTTP <- HTTP.httpGen
      httpFrag = http.toByteString.grouped(64).map(Left[ByteString, TimeoutException]).map(List(_))
      timeoutMixin <- Gen.listOfN(httpFrag.length, timeoutClusterGen)
    } yield timeoutMixin.zip(httpFrag).flatMap(t => t._1 ++ t._2)

  property("parseHTTP") = forAllNoShrink(httpDataWithTimeoutGen) { dataFlow => {
    val rawDataSource = Source(dataFlow)
    val future = rawDataSource.via(westgate.httpParseFlow).via(westgate.httpParserResultFlow).runWith(Sink.seq)
    val result = Await.result(future, FiniteDuration(1, "s"))
    (dataFlow.flatMap(
      { case Left(d) => d
      case Right(_) => ByteString.empty
      }
    ) == result.flatMap(_.data)) :| "same data"
  }
  }
}
