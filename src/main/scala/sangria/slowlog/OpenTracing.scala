package sangria.slowlog


import io.opentracing.{Span, Tracer}
import sangria.execution._
import sangria.schema.Context

import scala.collection.concurrent.TrieMap

class OpenTracing(implicit private val tracer: Tracer)
  extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {
  type QueryVal = TrieMap[Vector[Any], Span]
  type FieldVal = Unit

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) = {
    val span = tracer.buildSpan(context.operationName.getOrElse("UNNAMED")).start()
    TrieMap(Vector() -> span)
  }

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) =
    queryVal(Vector()).finish()

  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    val parentPath = extractPath(ctx.path).dropRight(1)
    val span = tracer
      .buildSpan(ctx.field.name)
      .asChildOf(queryVal(parentPath))
      .start()
    continue(queryVal.update(extractPath(ctx.path), span))
  }

  def afterField(
                  queryVal: QueryVal,
                  fieldVal: FieldVal,
                  value: Any,
                  mctx: MiddlewareQueryContext[Any, _, _],
                  ctx: Context[Any, _]) = {
    queryVal(extractPath(ctx.path)).finish()
    None
  }

  def fieldError(
                  queryVal: QueryVal,
                  fieldVal: FieldVal,
                  error: Throwable,
                  mctx: MiddlewareQueryContext[Any, _, _],
                  ctx: Context[Any, _]) =
    queryVal(extractPath(ctx.path)).finish()

  private def extractPath(path: ExecutionPath): Vector[String] =
    path.path.collect { case x: String => x }

}
