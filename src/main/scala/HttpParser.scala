import akka.util.ByteString

sealed trait HttpParsingStop

object HttpParser {
  def initState = ParsingRequest(ByteString.empty)

  private def getFirstLine(data: ByteString): (Option[ByteString], ByteString) = {
    val lineBreakerIndex = data.indexOfSlice("\r\n")
    if (lineBreakerIndex < 0) {
      (None, data)
    } else {
      val (firstLine, rest) = data.splitAt(lineBreakerIndex + 2)
      (Some(firstLine), rest)
    }
  }

  private def parseRequest(state: ParsingRequest, in: ByteString): HttpParser = {
    val totalInput = state.parsed ++ in
    val (maybeFirstLine, rest) = getFirstLine(totalInput)
    maybeFirstLine match {
      case None =>
        if (totalInput.length > 8192) {
          Invalid(totalInput)
        } else {
          ParsingRequest(totalInput)
        }
      case Some(firstLine) =>
        ParsingHeader(firstLine.utf8String.trim, List.empty, ByteString.empty, firstLine).input(rest)
    }
  }

  private def parseHeader(state: ParsingHeader, in: ByteString): HttpParser = {
    val toParse = state.crumb ++ in
    val (maybeFirstLine, rest) = getFirstLine(toParse)
    val totalInput = state.parsed ++ in
    maybeFirstLine match {
      case None =>
        if (toParse.length > 8196) {
          Invalid(totalInput)
        } else {
          ParsingHeader(state.request, state.header, toParse, totalInput)
        }
      case Some(firstLine) =>
        val indexOfColon = firstLine.indexOf(':')
        if (firstLine == ByteString("\r\n")) {
          Finished(state.request, state.header, totalInput)
        } else if (indexOfColon < 0) {
          Invalid(totalInput)
        } else if (state.header.length >= 99) {
          Invalid(totalInput)
        } else {
          val (_headerField, _headerValue) = firstLine.splitAt(indexOfColon)
          val headerField = _headerField.utf8String.toLowerCase.trim
          val headerValue = _headerValue.drop(1).utf8String.trim
          if (headerField.isEmpty || headerValue.isEmpty) {
            Invalid(totalInput)
          } else {
            val newHeader = (headerField, headerValue) :: state.header
            ParsingHeader(state.request,
              newHeader,
              ByteString.empty,
              state.parsed ++ firstLine.drop(state.crumb.length)).input(rest)
          }
        }

    }
  }

}

sealed abstract class HttpParser {
  def input(in: ByteString): HttpParser = this match {
    case _: HttpParsingStop => Passthrough(in)
    case s: ParsingRequest => HttpParser.parseRequest(s, in)
    case s: ParsingHeader => HttpParser.parseHeader(s, in)
  }
}

case class ParsingRequest(parsed: ByteString) extends HttpParser

case class ParsingHeader(request: String, header: List[(String, String)], crumb: ByteString, parsed: ByteString) extends HttpParser

case class Finished(request: String, header: List[(String, String)], parsed: ByteString) extends HttpParser with HttpParsingStop

case class Invalid(parsed: ByteString) extends HttpParser with HttpParsingStop

case class Passthrough(data: ByteString) extends HttpParser with HttpParsingStop