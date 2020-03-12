import akka.util.ByteString

import scala.collection.immutable.ListMap

sealed trait HttpParsingStop

object HttpParser {
  def initState = ParsingRequest(ByteString.empty)
}

sealed class HttpParser {
  def input(in: ByteString): HttpParser = this match {
    case _: Passthrough => Passthrough(in)
    case _: Invalid => Passthrough(in)
    case _: Finished => Passthrough(in)
  }
}

case class ParsingRequest(parsed: ByteString) extends HttpParser

case class ParsingHeader(request: String, header: ListMap[String, String], crumb: ByteString, parsed: ByteString) extends HttpParser

case class Finished(request: ByteString, header: ListMap[String, String], parsed: ByteString) extends HttpParser with HttpParsingStop

case class Invalid(parsed: ByteString) extends HttpParser with HttpParsingStop

case class Passthrough(data: ByteString) extends HttpParser with HttpParsingStop