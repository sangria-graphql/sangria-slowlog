package sangria.slowlog

import io.opentracing.{Span, Tracer}

import language.postfixOps
import org.slf4j.Logger
import sangria.ast.Document
import sangria.execution._
import sangria.marshalling.InputUnmarshaller
import sangria.schema.Context

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class SlowLog(
    logFn: Option[(Document, Option[String], Long) => Unit],
    threshold: FiniteDuration,
    addExtensions: Boolean)(implicit renderer: MetricRenderer)
    extends Middleware[Any]
    with MiddlewareAfterField[Any]
    with MiddlewareErrorField[Any]
    with MiddlewareExtension[Any] {
  type QueryVal = QueryMetrics
  type FieldVal = Long

  private val thresholdNanos = threshold.toNanos

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]): QueryMetrics =
    QueryMetrics(TrieMap.empty, TrieMap.empty, System.nanoTime(), addExtensions)

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]): Unit = ()

  def afterQueryExtensions(
      queryVal: QueryMetrics,
      context: MiddlewareQueryContext[Any, _, _]): Vector[Extension[_]] = {
    implicit val iu: InputUnmarshaller[Any] =
      context.inputUnmarshaller.asInstanceOf[InputUnmarshaller[Any]]
    val vars = context.variables.asInstanceOf[Any]
    val durationNanos = System.nanoTime() - queryVal.startNanos

    val updatedQuery = queryVal.enrichQuery(
      context.executor.schema,
      context.queryAst,
      context.operationName,
      vars,
      durationNanos,
      context.validationTiming.durationNanos,
      context.queryReducerTiming.durationNanos
    )

    if (durationNanos > thresholdNanos)
      logFn.foreach(fn => fn(updatedQuery, context.operationName, durationNanos))

    if (addExtensions)
      Vector(
        queryVal.extension(
          updatedQuery,
          durationNanos,
          context.validationTiming.durationNanos,
          context.queryReducerTiming.durationNanos))
    else
      Vector.empty
  }

  def beforeField(
      queryVal: QueryVal,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]): BeforeFieldResult[Any, Long] =
    continue(System.nanoTime())

  def afterField(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      value: Any,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]): None.type = {
    updateMetric(queryVal, fieldVal, ctx, success = true)

    None
  }

  def fieldError(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      error: Throwable,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]): Unit =
    updateMetric(queryVal, fieldVal, ctx, success = false)

  def updateMetric(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      ctx: Context[Any, _],
      success: Boolean): Unit = {
    val path = even(ctx.path.cacheKey)

    queryVal.update(path, ctx.parentType.name, ctx.field.name, success, fieldVal, System.nanoTime())
  }

  def even[T](v: Vector[T]): Vector[T] = v.iterator.zipWithIndex
    .filter(_._2 % 2 == 0)
    .map(_._1)
    .toVector
}

object SlowLog {
  private def renderQueryForLog(
      query: Document,
      operationName: Option[String],
      separateOp: Boolean): String =
    if (separateOp) query.separateOperation(operationName).fold("")(_.renderPretty)
    else query.renderPretty

  private def renderLog(
      query: Document,
      operationName: Option[String],
      durationNanos: Long,
      separateOp: Boolean)(implicit renderer: MetricRenderer): String =
    renderer.renderLogMessage(durationNanos, renderQueryForLog(query, operationName, separateOp))

  def apply(
      logger: Logger,
      threshold: FiniteDuration,
      addExtensions: Boolean = false,
      separateOp: Boolean = true)(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(
      Some((query, op, duration) => logger.warn(renderLog(query, op, duration, separateOp))),
      threshold,
      addExtensions)

  def log(
      logFn: (Long, String) => Unit,
      threshold: FiniteDuration,
      addExtensions: Boolean = false,
      separateOp: Boolean = true)(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(
      Some((query, op, duration) => logFn(duration, renderQueryForLog(query, op, separateOp))),
      threshold,
      addExtensions)

  def print(
      threshold: FiniteDuration = 0 seconds,
      addExtensions: Boolean = false,
      separateOp: Boolean = true)(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(
      Some((query, op, duration) => println(renderLog(query, op, duration, separateOp))),
      threshold,
      addExtensions)

  def extension(implicit renderer: MetricRenderer): SlowLog =
    new SlowLog(None, 0 seconds, true)

  def extractQueryMetrics(result: ExecutionResult[_, _]): Option[QueryMetrics] =
    result.middlewareVals.collectFirst { case (v: QueryMetrics, _: SlowLog) => v }

  lazy val apolloTracing: Middleware[Any] = ApolloTracingExtension

  def openTracing(parentSpan: Option[Span] = None, defaultOperationName: String = "UNNAMED")(
      implicit tracer: Tracer): Middleware[Any] =
    new OpenTracing(parentSpan, defaultOperationName)(tracer)
}
