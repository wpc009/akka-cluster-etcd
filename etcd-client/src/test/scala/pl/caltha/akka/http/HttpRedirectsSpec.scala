package pl.caltha.akka.http

import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RoutingSettings
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Millis
import org.scalatest.time.Span

class HttpRedirectsSpec extends FlatSpec with Matchers with ScalaFutures {

  implicit val system = ActorSystem()

  implicit val executionContext = system.dispatcher

  implicit val Materializer = ActorMaterializer()

  implicit val routingSettings = RoutingSettings.default(system)

  def mockServerFlow(numRedicects: Int): Flow[HttpRequest, HttpResponse, Unit] = {
    val route = get {
      pathPrefix(IntNumber) { num ⇒
        if (num < numRedicects)
          overrideStatusCode(StatusCodes.TemporaryRedirect) {
            respondWithHeader(Location(s"/${num + 1}")) {
              complete {
                "redirect"
              }
            }
          }
        else {
          complete {
            "ok"
          }
        }
      }
    }
    Route.handlerFlow(route)
  }

  def request(serverFlow: Flow[HttpRequest, HttpResponse, Unit]): Future[HttpResponse] =
    Source.single(HttpRequest(GET).withUri("/0")).via(serverFlow).runWith(Sink.head)

  implicit val patience = PatienceConfig(timeout = scaled(Span(1000, Millis)))

  "redirect-enabled client" should "handle a direct response" in {
    val flow = HttpRedirects.apply(mockServerFlow(numRedicects = 0), 3)
    whenReady(request(flow)) {
      resp ⇒
        resp.status shouldBe StatusCodes.OK
    }
  }

  it should "handle appropriate number redirects" in {
    val flow = HttpRedirects.apply(mockServerFlow(numRedicects = 3), 3)
    whenReady(request(flow)) {
      resp ⇒
        resp.status shouldBe StatusCodes.OK
    }
  }

  it should "handle a suspected redirect loop with 310 status" in {
    val flow = HttpRedirects.apply(mockServerFlow(numRedicects = 4), 3)
    whenReady(request(flow)) {
      resp ⇒
        resp.status.intValue() shouldBe 310
    }
  }
}