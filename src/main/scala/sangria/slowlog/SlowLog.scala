package sangria.slowlog

import language.postfixOps

import org.slf4j.Logger
import sangria.ast.Document
import sangria.execution._
import sangria.marshalling.InputUnmarshaller
import sangria.schema.Context

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class SlowLog(logFn: Option[(Document, Option[String], Long) ⇒ Unit], threshold: FiniteDuration, addExtentions: Boolean)(implicit renderer: MetricRenderer)
    extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] with MiddlewareExtension[Any] {
  type QueryVal = QueryMetrics
  type FieldVal = Long

  val thresholdNanos = threshold.toNanos

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) =
    QueryMetrics(TrieMap.empty, TrieMap.empty, System.nanoTime(), addExtentions)

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) = ()

  def afterQueryExtensions(queryVal: QueryMetrics, context: MiddlewareQueryContext[Any, _, _]): Vector[Extension[_]] = {
    implicit val iu = context.inputUnmarshaller.asInstanceOf[InputUnmarshaller[Any]]
    val vars = context.variables.asInstanceOf[Any]
    val durationNanos = System.nanoTime() - queryVal.startNanos

    val updatedQuery = queryVal.enrichQuery(
      context.executor.schema,
      context.queryAst,
      context.operationName,
      vars,
      durationNanos,
      context.validationTiming.durationNanos,
      context.queryReducerTiming.durationNanos)

    if (durationNanos > thresholdNanos)
      logFn.foreach(fn ⇒ fn(updatedQuery, context.operationName, durationNanos))
    
    if (addExtentions) {
      import sangria.marshalling.queryAst._
      import sangria.ast

      Vector(Extension(
        ast.ObjectValue(
          Vector(
            ast.ObjectField("metrics", ast.ObjectValue(
              Vector(
                ast.ObjectField("name", ast.StringValue("test name")),
                ast.ObjectField("executionTimeMs", ast.IntValue(123))))))): ast.Value))
    } else Vector.empty
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

    queryVal.update(path, ctx.parentType.name, ctx.field.name, success, fieldVal, System.nanoTime())
  }

  def even[T](v: Vector[T]): Vector[T] = v.zipWithIndex.filter(_._2 % 2 == 0).map(_._1)
}

object SlowLog {
  private def renderQueryForLog(query: Document, operationName: Option[String]): String =
    query.separateOperation(operationName).fold("")(_.renderPretty)

  private def renderLog(query: Document, operationName: Option[String], durationNanos: Long)(implicit renderer: MetricRenderer): String =
    s"Slow GraphQL query [${renderer.renderDuration(durationNanos)}].\n\n${renderQueryForLog(query, operationName)}"

  def apply(logger: Logger, threshold: FiniteDuration, addExtentions: Boolean = false)(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(Some((query, op, duration) ⇒ logger.warn(renderLog(query, op, duration))), threshold, addExtentions)

  def log(logFn: (Long, String) ⇒ Unit, threshold: FiniteDuration, addExtentions: Boolean = false)(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(Some((query, op, duration) ⇒ logFn(duration, renderQueryForLog(query, op))), threshold, addExtentions)

  def print(threshold: FiniteDuration = 0 seconds, addExtentions: Boolean = false)(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(Some((query, op, duration) ⇒ println(renderLog(query, op, duration))), threshold, addExtentions)

  def extension(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(None, 0 seconds, true)
}
