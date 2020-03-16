import org.scalacheck._
import org.scalacheck.Prop.{propBoolean, forAll}
import akka.util.ByteString


case class HTTP(request: String, header: List[(String, String)], body: ByteString) {
  def toByteString: ByteString = {
    ByteString(request) ++ ByteString("\r\n") ++
      ByteString(header.map(t => s"${t._1}: ${t._2}\r\n").mkString) ++
      ByteString("\r\n") ++
      body
  }
}


object HttpParserSpec extends Properties("HttpParser") {
  override def overrideParameters(p: Test.Parameters): Test.Parameters = p.withMinSuccessfulTests(1000)

  val genStringPair: Gen[(String, String)] = (for {f: String <- Gen.alphaStr
                                                   s: String <- Gen.alphaStr} yield (f, s))
    .suchThat(sp => sp._1.length + sp._2.length < 8000 && !sp._1.isEmpty && !sp._2.isEmpty)

  val genHeader: Gen[List[(String, String)]] = for {headerCount <- Gen.choose(0, 99)
                                                    headers <- Gen.listOfN(headerCount, genStringPair)} yield headers

  val httpGen: Gen[HTTP] = for {
    requestLine: String <- Gen.alphaStr.suchThat(_.length < 8000).suchThat(_.length > 0)
    header <- genHeader
    body <- Gen.asciiStr
  } yield HTTP(requestLine, header, ByteString(body))
  property("startsWith") = forAll(httpGen) { h => {
    val s = h.toByteString

    def isStoppedState(s: HttpParser): Boolean = s match {
      case _: HttpParsingStop => true
      case _ => false
    }

    val states = s.grouped(16)
      .scanLeft[HttpParser](HttpParser.initState)((s: HttpParser, b: ByteString) => s.input(b)).toList
    val stoppedStates: List[HttpParser] = states.filter(isStoppedState)
    val finalState = stoppedStates(0)
    val parsedData: ByteString = stoppedStates
      .flatMap[HttpParsingStop]({
      case x: HttpParsingStop => Some(x)
      case _ => None
    }).map(_.data).foldLeft(ByteString.empty)(_ ++ _)
    finalState match {
      case Finished(request, header, parsed) =>
        (request == h.request) :| "same request line" &&
          (header == h.header) :| "same headers" &&
          (parsedData == s) :| "same data"
      case _ => false :| "parse successfully"
    }
  }
  }
}

