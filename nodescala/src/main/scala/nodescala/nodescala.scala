package nodescala

import com.sun.net.httpserver._
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}
import scala.collection._
import scala.collection.JavaConversions._
import java.util.concurrent.{Executor, ThreadPoolExecutor, TimeUnit, LinkedBlockingQueue}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress

trait NodeScala {
  import NodeScala._

  def port: Int

  def createListener(relativePath: String): Listener

  /** Responds to the request by writing chunks to the output stream.
   *  Must periodically check `token.isCancelled` to terminate early.
   */
  private def respond(exchange: Exchange, token: CancellationToken, response: Response): Unit = {
    async {
      try {
        for (chunk <- response if !token.isCancelled) {
          exchange.write(chunk)
        }
      } finally {
        exchange.close()
      }
    }
  }

  /** Starts the HTTP server, listens for requests, and handles them asynchronously. */
  def start(relativePath: String)(handler: Request => Response): Subscription = {
    val listener = createListener(relativePath)
    val listenerSubscription = listener.start()

    // Create a cancellable computation that repeatedly serves requests
    val serverSubscription = Future.run() { token =>
      async {
        while (!token.isCancelled) {
          val (req, exch) = await(listener.nextRequest())
          respond(exch, token, handler(req))
        }
      }
    }

    // Return a composite subscription that stops both listener and server loop
    Subscription.combine(listenerSubscription, serverSubscription)
  }
}


object NodeScala {

  type Request = Map[String, List[String]]
  type Response = Iterator[String]

  trait Exchange {
    def write(s: String): Unit
    def close(): Unit
    def request: Request
  }

  object Exchange {
    def apply(exchange: HttpExchange) = new Exchange {
      val os = exchange.getResponseBody()
      exchange.sendResponseHeaders(200, 0L)
      def write(s: String) = os.write(s.getBytes)
      def close() = os.close()
      def request: Request = {
        val headers = for ((k, vs) <- exchange.getRequestHeaders) yield (k, vs.toList)
        immutable.Map() ++ headers
      }
    }
  }

  trait Listener {
    def port: Int
    def relativePath: String
    def start(): Subscription
    def createContext(handler: Exchange => Unit): Unit
    def removeContext(): Unit

    def nextRequest(): Future[(Request, Exchange)] = {
      val p = Promise[(Request, Exchange)]()
      createContext(xchg => {
        val req = xchg.request
        removeContext()
        p.success((req, xchg))
      })
      p.future
    }
  }

  object Listener {
    class Default(val port: Int, val relativePath: String) extends Listener {
      private val s = HttpServer.create(new InetSocketAddress(port), 0)
      private val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue)
      s.setExecutor(executor)

      def start() = {
        s.start()
        new Subscription {
          def unsubscribe() = {
            s.stop(0)
            executor.shutdown()
          }
        }
      }

      def createContext(handler: Exchange => Unit) = s.createContext(relativePath, new HttpHandler {
        def handle(httpxchg: HttpExchange) = handler(Exchange(httpxchg))
      })

      def removeContext() = s.removeContext(relativePath)
    }
  }

  class Default(val port: Int) extends NodeScala {
    def createListener(relativePath: String) = new Listener.Default(port, relativePath)
  }

  /** Combine two subscriptions into one. */
  object Subscription {
    def combine(s1: Subscription, s2: Subscription): Subscription = new Subscription {
      def unsubscribe() = {
        s1.unsubscribe()
        s2.unsubscribe()
      }
    }
  }
}
