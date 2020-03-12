import akka.util.ByteString

import scala.collection.immutable.ListMap

sealed trait HttpParsingStop

object HttpParser {
  def initState = ParsingRequest(ByteString.empty)

  def parseRequest(state: ParsingRequest, in: ByteString): HttpParser = {
    val totalInput = state.parsed ++ in
    val firstLine = totalInput.takeWhile(_ != '\n')
    val firstLineLength = firstLine.length
    val firstLineStr = firstLine.utf8String
    if (firstLine.endsWith(List('\r', '\n'))) {
      val unParsed = totalInput.drop(firstLineLength)
      val nextState = ParsingHeader(firstLineStr, ListMap.empty, ByteString.empty, firstLine)
      nextState.input(unParsed)
    } else if (totalInput.length > 8192) {
      Invalid(totalInput)
    } else {
      ParsingRequest(totalInput)
    }
  }

}

sealed class HttpParser {

  def input(in: ByteString): HttpParser = this match {
    case _: Passthrough => Passthrough(in)
    case _: Invalid => Passthrough(in)
    case _: Finished => Passthrough(in)
    case s: ParsingRequest => HttpParser.parseRequest(s, in)
  }
}

case class ParsingRequest(parsed: ByteString) extends HttpParser

case class ParsingHeader(request: String, header: ListMap[String, String], crumb: ByteString, parsed: ByteString) extends HttpParser

case class Finished(request: ByteString, header: ListMap[String, String], parsed: ByteString) extends HttpParser with HttpParsingStop

case class Invalid(parsed: ByteString) extends HttpParser with HttpParsingStop

case class Passthrough(data: ByteString) extends HttpParser with HttpParsingStop