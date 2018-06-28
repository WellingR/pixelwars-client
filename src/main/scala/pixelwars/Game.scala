package pixelwars

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCode, Uri}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.{ByteString, Timeout}
import pixelwars.Game.{BoardResult, RegisterResponse, RequestedBoard, TokenHeader}
import spray.json._
import DefaultJsonProtocol._
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import org.slf4j.LoggerFactory
import akka.pattern.ask
import akka.pattern.after
import pixelwars.SnakeBoard.{Advice, DetermineMove, SeenPixels}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Random, Try}

object Game {

  val Empty = "#000000"

  val uri = Uri("http://10.248.30.235:9000")
//  val uri = Uri("http://localhost:9000")

  def main(args: Array[String]): Unit = {

    implicit val system: ActorSystem = ActorSystem("pixelwar")
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher
    implicit val http: HttpExt = Http()

    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val client: GameClient = new GameClient(uri)


    val f = client.register( s"Root-${Random.nextInt(100)}").flatMap { registerResult =>
      implicit val token: TokenHeader = TokenHeader(registerResult.token)
      val initialClaim = client.claim(Random.nextInt(registerResult.boardSize), Random.nextInt(registerResult.boardSize))
      recursiveClaim(system.actorOf(SnakeBoard.props(registerResult, client)), initialClaim)
    }



    f.onComplete { result =>
      println(result)
      system.terminate()
    }

  }


  def recursiveClaim(board: ActorRef, previousClaim: Future[RequestedBoard])(implicit token: TokenHeader, client: GameClient, ec: ExecutionContext, Timeout: Timeout, system: ActorSystem): Future[RequestedBoard] = {
    for {
      prevRequest <- previousClaim
      _ = board ! SeenPixels(prevRequest.toSituation)
      Advice(x, y, extra) <- board.ask(DetermineMove(prevRequest.x, prevRequest.y)).mapTo[Advice]
      result <- extra match {
        case None => recursiveClaim(board, client.claim(x, y)).fallbackTo(previousClaim)
        case Some((x2, y2)) =>
          val f1 = client.claim(x, y)
          f1.foreach(rc => board ! SeenPixels(rc.toSituation))
          val f2 = akka.pattern.after(5.millis, system.scheduler)(client.claim(x2, y2))
          recursiveClaim(board, f2.fallbackTo(f1).fallbackTo(previousClaim))
      }
    } yield result
  }

  // aanmeldiing
  // post /players/NAME

  case class RegisterResponse(boardSize: Int, playerName: String, color: String, token: String)
  implicit def jsonRegisterResponse: RootJsonFormat[RegisterResponse] = jsonFormat4(RegisterResponse)


  // claim pixel
  // http://host:port/pixes/x/y
  case class BoardResult(x: Int, y: Int, surroundingFields: Seq[Seq[String]])

  implicit def jsonClaimResult: RootJsonFormat[BoardResult] = jsonFormat3(BoardResult)


  // explore
  // http://host:port/pixels/x/y/radius (delay 20ms x radius)


  final class TokenHeader(val value: String) extends ModeledCustomHeader[TokenHeader] {
    override def renderInRequests: Boolean = true
    override def renderInResponses: Boolean = true
    override val companion: TokenHeader.type = TokenHeader
  }
  object TokenHeader extends ModeledCustomHeaderCompanion[TokenHeader] {
    override val name: String = "Token"
    override def parse(value: String): Try[TokenHeader] = Try(new TokenHeader(value))
  }

  case class RequestedBoard(x: Int, y: Int, result: BoardResult) {
    def toSituation: Map[(Int, Int), String] = {
      (for {
        (seq, relativeX) <- result.surroundingFields.zipWithIndex
        (color, relativeY) <- seq.zipWithIndex
      } yield {
        (x + relativeX - result.x, y + relativeY - result.y) -> color
      }).toMap
    }

    def nextMove: (Int, Int) = {
      val situation = (for {
        (seq, relativeX) <- result.surroundingFields.zipWithIndex
        (color, relativeY) <- seq.zipWithIndex
      } yield {
        (x + relativeX - result.x, y + relativeY - result.y) -> (color == Empty, relativeX == result.x || relativeY == result.y)
      }).toMap

      val possibleMoves = situation.collect { case ((xx, yy), (true, true)) =>
        val possibleSideSteps = Seq(
          situation.getOrElse(xx + 1 -> yy, false -> false)._1,
          situation.getOrElse(xx - 1 -> yy, false -> false)._1,
          situation.getOrElse(xx -> (yy + 1), false -> false)._1,
          situation.getOrElse(xx -> (yy - 1), false -> false)._1
        ).count(_ == true)
        (xx, yy) -> possibleSideSteps
      }

      possibleMoves.maxBy(_._2)._1
    }
  }

}

class GameClient(val baseUri: Uri)(implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext, http: HttpExt) {

  private val log = LoggerFactory.getLogger(getClass)

  def register(name: String): Future[RegisterResponse] = {
    val req = HttpRequest(HttpMethods.POST, baseUri.withPath(baseUri.path / "players" / name))
    executeRequest[RegisterResponse](req)
  }

  def claim(x: Int, y: Int)(implicit header: TokenHeader): Future[RequestedBoard] = {
    val req = HttpRequest(HttpMethods.PUT, baseUri.withPath(baseUri.path / "pixels" / x.toString / y.toString), headers = List(header))
    executeRequest[BoardResult](req).map { response =>
      RequestedBoard(x, y, response)
    }
  }

  def exploreRequest(x: Int, y: Int, radius: Int = 1)(implicit header: TokenHeader): HttpRequest = {
    HttpRequest(HttpMethods.GET, baseUri.withPath(baseUri.path / "pixels" / x.toString / y.toString / radius.toString), headers = List(header))
  }

  def explore(x: Int, y: Int, radius: Int = 1)(implicit header: TokenHeader): Future[RequestedBoard] = {
    val req = exploreRequest(x, y, radius)
    executeRequest[BoardResult](req).map { response =>
      RequestedBoard(x, y, response)
    }
  }

  def executeRequest[T: RootJsonReader](req: HttpRequest): Future[T] = for {
    response <- http.singleRequest(req)
    bytes <- response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
  } yield {
    val json = bytes.utf8String.parseJson
    log.info("Got response with status {}, and body {}", response.status: Any, json.prettyPrint: Any)
    json.convertTo[T]
  }

}
