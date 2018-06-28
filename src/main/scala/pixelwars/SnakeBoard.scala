package pixelwars

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.HttpExt
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import pixelwars.Game.{BoardResult, RegisterResponse, RequestedBoard, TokenHeader}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}
import scala.util.control.NonFatal
//import game.GameManager.Player
//import models._
import spray.json._
import scala.concurrent.duration._
import scala.collection.mutable
object SnakeBoard {
  final val Empty: String = "#000000"


  def props(r: RegisterResponse, client: GameClient)(implicit mat: Materializer, ec: ExecutionContext, http: HttpExt, tokenHeader: TokenHeader): Props = Props(new SnakeBoard(r.boardSize, r.color, client))

  case class SeenPixels(pixels: Map[(Int, Int), String])

  case class DetermineMove(fromX: Int, fromY: Int)

  case class Advice(x: Int, y: Int, extraMove: Option[(Int, Int)])

}

class SnakeBoard(boardSize: Int, myColor: String, client: GameClient)(implicit mat: Materializer, ec: ExecutionContext, http: HttpExt, tokenHeader: TokenHeader) extends Actor with ActorLogging {
  import SnakeBoard._

  // containers of the state of the Board
  val fields: mutable.Map[(Int, Int), Option[String]] = mutable.Map.empty.withDefaultValue(None)

  val coordinatesToExplore: immutable.IndexedSeq[(Int, Int)] = for {
    x <- 1.to(boardSize).by(4)
    y <- 1.to(boardSize).by(4)
  } yield (x, y)

  val pool = http.cachedHostConnectionPool[(Int, Int)](client.baseUri.authority.host.address(), client.baseUri.effectivePort)
  val requestSource = Source.cycle(() => coordinatesToExplore.iterator).map { case tuple @ (x, y) =>
    client.exploreRequest(x, y, radius = 3) -> tuple
  }.via(pool)
    .mapAsync(20) { case (responseTry, (x, y)) =>
      val f = for {
        response <- Future.fromTry(responseTry)
        bytes <- response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
      } yield {
        val json = bytes.utf8String.parseJson
        log.warning("Got response with status {}, and body {}", response.status: Any, json.prettyPrint: Any)
        val boardResult = json.convertTo[BoardResult]
        RequestedBoard(x, y, boardResult)
      }
      f.transform(Success.apply)
    }.runForeach {
    case scala.util.Failure(f) =>
      log.warning("explore failed because of {}", f)
    case Success(result) =>
      self ! SeenPixels(result.toSituation)
  }


  override def receive: Receive = {
    case SeenPixels(pixels) =>
      pixels.filter(_._2 != Empty).foreach { case (coordinate, color) =>
        fields(coordinate) = Some(color)
      }

//      def bar: String = "---" * boardSize + "\n"
//
//      def row(x: Int): String = {
//        0.to(boardSize)
//          .map(y => if (fields.getOrElse((x, y), None).nonEmpty) "X" else " ")
//          .mkString("|","|","|\n") + bar
//      }
//
//      println(bar + 0.to(boardSize).map(row).foldLeft("")(_ + _))
    case DetermineMove(fromX, fromY) =>
      try {
        val (x, y) = bestMove(fromX, fromY)
        val move2 = Try(bestMove(x, y)).toOption
        sender() ! Advice(x, y, move2)
      } catch {
        case NonFatal(e) =>
          sender() ! Status.Failure(e)
      }
  }

  def bestMove(fromX: Int, fromY: Int): (Int, Int) = {
    val scoreLeft = Stream.iterate((fromX, fromY)){ case (x, y) => (x - 1, y)}.drop(1).takeWhile{ case (x, y) => x >= 0 && x < boardSize && fields((x, fromY)).isEmpty}
    val scoreRight =  Stream.iterate((fromX, fromY)){ case (x, y) => (x + 1, y)}.drop(1).takeWhile{ case (x, y) => x >= 0 && x < boardSize && fields((x, fromY)).isEmpty}
    val scoreUp = Stream.iterate((fromX, fromY)){ case (x, y) => (x , y - 1)}.drop(1).takeWhile{ case (x, y) => y >= 0 && y < boardSize && fields((fromX, y)).isEmpty}
    val scoreDown = Stream.iterate((fromX, fromY)){ case (x, y) => (x , y + 1)}.drop(1).takeWhile{ case (x, y) => y >= 0 && y < boardSize && fields((fromX, y)).isEmpty}

    val bestStream = Seq(scoreLeft, scoreRight, scoreUp, scoreDown).maxBy(_.size)
    bestStream.head
  }
}