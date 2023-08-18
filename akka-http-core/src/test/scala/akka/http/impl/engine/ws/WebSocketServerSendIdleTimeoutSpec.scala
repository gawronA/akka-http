/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.Done
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.AttributeKeys.webSocketUpgrade
import akka.http.scaladsl.model.Uri.apply
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.testkit._
import org.scalatest.concurrent.Eventually

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success}

class WebSocketServerSendIdleTimeoutSpec extends AkkaSpecWithMaterializer(
  """
     akka {
       stream.materializer.debug.fuzzing-mode=off
       http.server.websocket.log-frames = on
       http.client.websocket.log-frames = on
       http.server.websocket.send-idle-timeout = 1s
     }
  """) with Eventually {

  "A WebSocket server" must {

    "terminate the handler flow with an akka.stream.StreamIdleTimeoutException when elements are not sent within send-idle-timeout" in Utils.assertAllStagesStopped {
      import system.dispatcher
      val handlerTermination = Promise[Done]()
      val handler = Flow[Message].map(identity).watchTermination() { (_, terminationFuture) =>
        terminationFuture.onComplete {
          case Success(_) => handlerTermination.trySuccess(Done)
          case Failure(exception) => handlerTermination.tryFailure(exception)
        }
      }

      val binding = Http().newServerAt("localhost", 0)
        .bindSync({
          _.attribute(webSocketUpgrade).get.handleMessages(handler.recover { case ex =>
            handlerTermination.failure(ex)
            TextMessage("dummy")
          }, None)
        }).futureValue(timeout(3.seconds.dilated))
      val myPort = binding.localAddress.getPort

      Source(1 to 10).map(_ => {
        Await.result(Future(Thread.sleep(200)), Duration(3, TimeUnit.SECONDS))
        TextMessage("dummy")
      })
        .via(Http().webSocketClientFlow(WebSocketRequest("ws://127.0.01:" + myPort))).to(Sink.ignore).run()

      handlerTermination.future.failed.futureValue shouldBe a[akka.stream.StreamIdleTimeoutException]
      binding.unbind()
    }
  }
}
