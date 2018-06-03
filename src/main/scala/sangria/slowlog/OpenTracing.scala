package sangria.slowlog


import io.opentracing.{Span, Tracer}
import sangria.execution._
import sangria.schema.Context

import scala.collection.concurrent.TrieMap

final case class SpanAttachment(span: Span) extends MiddlewareAttachment

class OpenTracing(implicit private val tracer: Tracer, defaultOperationName: String = "UNNAMED")
  extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {
  type QueryVal = TrieMap[Vector[Any], Span]
  type FieldVal = Unit

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) = {
    val span = tracer.buildSpan(context.operationName.getOrElse(defaultOperationName)).start()
    TrieMap(Vector() -> span)
  }

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) =
    queryVal.get(Vector()).foreach(_.finish())

  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    val parentPath = extractPath(ctx.path).dropRight(1)
    val span =
      queryVal.get(parentPath).map{
        parentSpan =>
          tracer
            .buildSpan(ctx.field.name)
            .asChildOf(parentSpan)
            .start()
      }.getOrElse{
        tracer
          .buildSpan(ctx.field.name)
          .start()
      }

    BeforeFieldResult(queryVal.update(extractPath(ctx.path), span), attachment = Some(SpanAttachment(span)))
  }

  def afterField(
                  queryVal: QueryVal,
                  fieldVal: FieldVal,
                  value: Any,
                  mctx: MiddlewareQueryContext[Any, _, _],
                  ctx: Context[Any, _]) = {
    queryVal.get(extractPath(ctx.path)).foreach(_.finish())
    None
  }

  def fieldError(
                  queryVal: QueryVal,
                  fieldVal: FieldVal,
                  error: Throwable,
                  mctx: MiddlewareQueryContext[Any, _, _],
                  ctx: Context[Any, _]) =
    queryVal.get(extractPath(ctx.path)).foreach(_.finish())

  private def extractPath(path: ExecutionPath): Vector[String] =
    path.path.collect { case x: String => x }

}
