package sangria.slowlog

import java.util.concurrent.TimeUnit

import sangria.execution.{Middleware, MiddlewareAfterField, MiddlewareErrorField, MiddlewareQueryContext}
import sangria.marshalling.InputUnmarshaller
import sangria.schema.Context

import scala.collection.concurrent.TrieMap

class SlowLogMiddleware(unit: TimeUnit = TimeUnit.MILLISECONDS)(implicit renderer: MetricRenderer) extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {
  type QueryVal = QueryMetrics
  type FieldVal = Long

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) =
    QueryMetrics(TrieMap.empty, System.nanoTime())

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) = {
    val sepOps = context.queryAst.separateOperations
    val operationAst =
      if (sepOps.size == 1)
        Some(sepOps.head._2)
      else
        sepOps.get(context.operationName)

    implicit val iu = context.inputUnmarshaller.asInstanceOf[InputUnmarshaller[Any]]
    val vars = context.variables.asInstanceOf[Any]

    operationAst.foreach { op ⇒
      val updatedQuery = queryVal.enrichQuery(
        context.executor.schema,
        op,
        context.operationName,
        vars,
        System.nanoTime() - queryVal.startNanos,
        context.validationTiming.durationNanos,
        context.queryReducerTiming.durationNanos,
        unit)
      println(updatedQuery.renderPretty)
    }
  }

  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) =
    continue(System.nanoTime())

  def afterField(queryVal: QueryVal, fieldVal: FieldVal, value: Any, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    updateMetric(queryVal, fieldVal, ctx, success = true)

    None
  }

  def fieldError(queryVal: QueryVal, fieldVal: FieldVal, error: Throwable, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) =
    updateMetric(queryVal, fieldVal, ctx, success = false)

  def updateMetric(queryVal: QueryVal, fieldVal: FieldVal, ctx: Context[Any, _], success: Boolean): Unit = {
    val path = even(ctx.path.cacheKey)

    queryVal.update(path, ctx.parentType.name, success, fieldVal, System.nanoTime())
  }

  def even[T](v: Vector[T]): Vector[T] = v.zipWithIndex.filter(_._2 % 2 == 0).map(_._1)
}
