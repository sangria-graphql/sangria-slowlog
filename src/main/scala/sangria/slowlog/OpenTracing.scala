package sangria.slowlog

import io.opentracing.{Span, Tracer}
import sangria.execution._
import sangria.schema.Context

import scala.collection.concurrent.TrieMap

final case class SpanAttachment(span: Span) extends MiddlewareAttachment

class OpenTracing(defaultOperationName: String = "UNNAMED")(implicit tracer: Tracer)
  extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {
  type QueryVal = TrieMap[Vector[Any], Span]
  type FieldVal = Unit

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) = {
    val span = tracer
      .buildSpan(context.operationName.getOrElse(defaultOperationName))
      .withTag("type", "graphql-query")
      .start()
    TrieMap(Vector() -> span)
  }

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) =
    queryVal.get(Vector()).foreach(_.finish())

  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    val path = ctx.path.path
    val parentPath = path
      .dropRight(1)
      .reverse
      .dropWhile {
        case _: String => false
        case _: Int    => true
      }
      .reverse

    val span =
      queryVal
        .get(parentPath)
        .map { parentSpan =>
          tracer
            .buildSpan(ctx.field.name)
            .withTag("type", "graphql-field")
            .asChildOf(parentSpan)
        }
        .getOrElse {
          tracer
            .buildSpan(ctx.field.name)
            .withTag("type", "graphql-field")
        }
        .start()

    BeforeFieldResult(queryVal.update(ctx.path.path, span), attachment = Some(SpanAttachment(span)))
  }

  def afterField(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      value: Any,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]) = {
    queryVal.get(ctx.path.path).foreach(_.finish())
    None
  }

  def fieldError(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      error: Throwable,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]) =
    queryVal.get(ctx.path.path).foreach(_.finish())
}
