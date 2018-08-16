package sangria.slowlog

import io.opentracing.{Scope, Span, Tracer}
import sangria.execution._
import sangria.schema.Context

import scala.collection.concurrent.TrieMap

class OpenTracing(parentSpan: Option[Span] = None, defaultOperationName: String = "UNNAMED")(implicit tracer: Tracer)
  extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {
  type QueryVal = TrieMap[Vector[Any], Scope]
  type FieldVal = Unit

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) = {
    val builder = tracer
      .buildSpan(context.operationName.getOrElse(defaultOperationName))
      .withTag("type", "graphql-query")

    val span =
      parentSpan match {
        case Some(parent) ⇒ builder.asChildOf(parent)
        case None ⇒ builder
      }

    TrieMap(Vector.empty → span.startActive(false))
  }

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) =
    queryVal.get(Vector.empty).foreach(resp => {
      resp.span().finish()
      resp.close()
    })

  def beforeField(queryVal: QueryVal, mctx: MiddlewareQueryContext[Any, _, _], ctx: Context[Any, _]) = {
    val path = ctx.path.path
    val parentPath = path
      .dropRight(1)
      .reverse
      .dropWhile {
        case _: String ⇒ false
        case _: Int ⇒ true
      }
      .reverse

    val scope =
      queryVal
        .get(parentPath)
        .map { parentScope ⇒
          tracer
            .buildSpan(ctx.field.name)
            .withTag("type", "graphql-field")
            .asChildOf(parentScope.span())
        }
        .getOrElse {
          tracer
            .buildSpan(ctx.field.name)
            .withTag("type", "graphql-field")
        }
        .startActive(false)

    BeforeFieldResult(queryVal.update(ctx.path.path, scope), attachment = Some(ScopeAttachment(scope)))
  }

  def afterField(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      value: Any,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]) = {
    queryVal.get(ctx.path.path).foreach(scope => {
      scope.span().finish()
      scope.close()
    })
    None
  }

  def fieldError(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      error: Throwable,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]) =
    queryVal.get(ctx.path.path).foreach(scope => {
      scope.span().finish()
      scope.close()
    })
}

final case class ScopeAttachment(span: Scope) extends MiddlewareAttachment