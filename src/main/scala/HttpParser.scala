import akka.util.ByteString

import scala.collection.immutable.ListMap

sealed trait HttpParsingStop

object HttpParser {
  def initState = ParsingRequest(ByteString.empty)
}

sealed class HttpParser

case class ParsingRequest(parsed: ByteString) extends HttpParser

case class ParsingHeader(request: String, header: ListMap[String, String], crumb: ByteString, parsed: ByteString) extends HttpParser

case class Finished(request: ByteString, header: ListMap[String, String], parsed: ByteString) extends HttpParser with HttpParsingStop

case class Invalid(parsed: ByteString) extends HttpParser with HttpParsingStop

case class Passthrough(data: ByteString) extends HttpParser with HttpParsingStop