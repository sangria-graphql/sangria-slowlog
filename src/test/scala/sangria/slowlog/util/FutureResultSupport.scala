package sangria.slowlog.util

import sangria.execution.{ErrorWithResolver, QueryAnalysisError}
import sangria.marshalling.ResultMarshallerForType

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps

trait FutureResultSupport {
  implicit class FutureResult[T](f: Future[T]) {
    def await: T = Await.result(f, 10.seconds)
    def await(duration: Duration): T = Await.result(f, duration)

    def awaitAndRecoverQueryAnalysis(implicit m: ResultMarshallerForType[T]): T =
      Await.result(recoverQueryAnalysis, 10.seconds)

    def recoverQueryAnalysis(implicit m: ResultMarshallerForType[T]): Future[T] = f.recover {
      case analysisError: QueryAnalysisError =>
        analysisError.resolveError(m.marshaller).asInstanceOf[T]
    }

    def awaitAndRecoverQueryAnalysisScala(implicit ev: T =:= Any): Any =
      Await.result(recoverQueryAnalysisScala, 10.seconds)

    def recoverQueryAnalysisScala(implicit ev: T =:= Any): Future[Any] = f.recover {
      case analysisError: ErrorWithResolver => analysisError.resolveError
    }
  }

  object sync {
    val executionContext: ExecutionContextExecutor =
      ExecutionContext.fromExecutor((command: Runnable) => command.run())
  }
}
