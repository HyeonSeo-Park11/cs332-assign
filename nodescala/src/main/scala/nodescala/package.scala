import scala.language.postfixOps
import scala.io.StdIn
import scala.util._
import scala.util.control.NonFatal
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.async.Async.{async, await}

/** Contains basic data types, data structures and `Future` extensions.
 */
package object nodescala {

  /** Adds extensions methods to the `Future` companion object.
   */
  implicit class FutureCompanionOps(val f: Future.type) extends AnyVal {

    /** Returns a future that is always completed with `value`. */
    def always[T](value: T): Future[T] = Promise.successful(value).future

    /** Returns a future that is never completed. */
    def never[T]: Future[T] = Promise[T]().future

    /** Returns a future holding the list of values of all the futures in `fs`. */
    def all[T](fs: List[Future[T]]): Future[List[T]] = {
      Future.sequence(fs)
    }

    /** Returns the future that completes first among `fs`. */
    def any[T](fs: List[Future[T]]): Future[T] = {
      val p = Promise[T]()
      fs.foreach(_.onComplete(p.tryComplete))
      p.future
    }

    /** Returns a future with a unit value that is completed after time `t`. */
    def delay(t: Duration): Future[Unit] = Future {
      blocking {
        Thread.sleep(t.toMillis)
      }
    }
    def userInput(message: String): Future[String] = Future { blocking { StdIn.readLine(message) } }

    /** Creates a cancellable context for an execution and runs it. */
    def run()(f: CancellationToken => Future[Unit]): Subscription = {
      val cts = CancellationTokenSource()
      f(cts.cancellationToken)
      cts
    }
  }

  /** Adds extension methods to future objects. */
  implicit class FutureOps[T](val f: Future[T]) extends AnyVal {

    /** Returns the result of this future if it is completed now. */
    def now: T = f.value match {
      case Some(Success(v)) => v
      case Some(Failure(e)) => throw e
      case None => throw new NoSuchElementException("Future not completed yet")
    }

    /** Continues the computation of this future by passing itself to `cont`. */
    def continueWith[S](cont: Future[T] => S): Future[S] = {
      val p = Promise[S]()
      f.onComplete(_ => try p.success(cont(f)) catch {
        case NonFatal(e) => p.failure(e)
      })
      p.future
    }

    /** Continues the computation of this future by passing its result to `cont`. */
    def continue[S](cont: Try[T] => S): Future[S] = {
      val p = Promise[S]()
      f.onComplete { result =>
        try p.success(cont(result))
        catch { case NonFatal(e) => p.failure(e) }
      }
      p.future
    }
  }

  /** Subscription objects are used to be able to unsubscribe from some event source. */
  trait Subscription {
    def unsubscribe(): Unit
  }

  object Subscription {
    /** Combines two subscriptions into one. */
    def apply(s1: Subscription, s2: Subscription) = new Subscription {
      def unsubscribe() {
        s1.unsubscribe()
        s2.unsubscribe()
      }
    }
  }

  /** Used to check if cancellation was requested. */
  trait CancellationToken {
    def isCancelled: Boolean
    def nonCancelled = !isCancelled
  }

  /** The `CancellationTokenSource` cancels its token when unsubscribed. */
  trait CancellationTokenSource extends Subscription {
    def cancellationToken: CancellationToken
  }

  object CancellationTokenSource {
    def apply() = new CancellationTokenSource {
      val p = Promise[Unit]()
      val cancellationToken = new CancellationToken {
        def isCancelled = p.future.value != None
      }
      def unsubscribe() {
        p.trySuccess(())
      }
    }
  }
}
