package sangria.slowlog

import io.opentracing.{Scope, Span, Tracer}
import sangria.execution._
import sangria.schema.Context

import scala.collection.concurrent.TrieMap

class OpenTracing(parentSpan: Option[Span] = None, defaultOperationName: String = "UNNAMED")(implicit tracer: Tracer)
  extends Middleware[Any] with MiddlewareAfterField[Any] with MiddlewareErrorField[Any] {

  type QueryVal = TrieMap[Vector[Any], (Span, Scope)]
  type FieldVal = Unit

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]) = {
    val builder = tracer
      .buildSpan(context.operationName.getOrElse(defaultOperationName))
      .withTag("type", "graphql-query")

    val spanBuilder =
      parentSpan match {
        case Some(parent) ⇒ builder.asChildOf(parent)
        case None ⇒ builder
      }

    val span = spanBuilder.start()
    val scope = tracer.activateSpan(span)

    TrieMap(Vector.empty → (span, scope))
  }

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]) =
    queryVal.get(Vector.empty).foreach { case (span, scope) =>
      span.finish()
      scope.close()
    }

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

    val spanBuilder =
      queryVal
        .get(parentPath)
        .map { case (parentSpan, _) ⇒
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


    val span = spanBuilder.start()
    val scope = tracer.activateSpan(span)

    BeforeFieldResult(queryVal.update(ctx.path.path, (span, scope)), attachment = Some(ScopeAttachment(span, scope)))
  }

  def afterField(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      value: Any,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]) = {
    queryVal.get(ctx.path.path).foreach { case (span, scope) =>
      span.finish()
      scope.close()
    }
    None
  }

  def fieldError(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      error: Throwable,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]) =
    queryVal.get(ctx.path.path).foreach { case (span, scope) =>
      span.finish()
      scope.close()
    }
}

final case class ScopeAttachment(span: Span, scope: Scope) extends MiddlewareAttachment
