package sangria.slowlog

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

import sangria.ast._
import sangria.execution._
import sangria.schema.Context
import sangria.marshalling.queryAst._
import sangria.renderer.SchemaRenderer

import scala.collection.JavaConverters._

object ApolloTracingExtension
    extends Middleware[Any]
    with MiddlewareExtension[Any]
    with MiddlewareAfterField[Any]
    with MiddlewareErrorField[Any] {
  type QueryVal = QueryTrace
  type FieldVal = Long

  def beforeQuery(context: MiddlewareQueryContext[Any, _, _]): QueryTrace =
    QueryTrace(Instant.now(), System.nanoTime(), new ConcurrentLinkedQueue)

  def afterQuery(queryVal: QueryVal, context: MiddlewareQueryContext[Any, _, _]): Unit = ()

  def beforeField(
      queryVal: QueryVal,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]): BeforeFieldResult[Any, FieldVal] =
    continue(System.nanoTime())

  def afterField(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      value: Any,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]): None.type = {
    updateMetric(queryVal, fieldVal, ctx)
    None
  }

  def fieldError(
      queryVal: QueryVal,
      fieldVal: FieldVal,
      error: Throwable,
      mctx: MiddlewareQueryContext[Any, _, _],
      ctx: Context[Any, _]): Unit =
    updateMetric(queryVal, fieldVal, ctx)

  def updateMetric(queryVal: QueryVal, fieldVal: FieldVal, ctx: Context[Any, _]): Unit =
    queryVal.fieldData.add(
      ObjectValue(
        "path" -> ListValue(
          ctx.path.path.map(queryAstResultMarshaller.scalarNode(_, "Any", Set.empty))),
        "parentType" -> StringValue(ctx.parentType.name),
        "fieldName" -> StringValue(ctx.field.name),
        "returnType" -> StringValue(SchemaRenderer.renderTypeName(ctx.field.fieldType)),
        "startOffset" -> BigIntValue(fieldVal - queryVal.startNanos),
        "duration" -> BigIntValue(System.nanoTime() - fieldVal)
      ))

  def afterQueryExtensions(
      queryVal: QueryVal,
      context: MiddlewareQueryContext[Any, _, _]): Vector[Extension[_]] =
    Vector(
      Extension(
        ObjectValue("tracing" -> ObjectValue(
          "version" -> IntValue(1),
          "startTime" -> StringValue(DateTimeFormatter.ISO_INSTANT.format(queryVal.startTime)),
          "endTime" -> StringValue(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
          "duration" -> BigIntValue(System.nanoTime() - queryVal.startNanos),
          "execution" -> ObjectValue("resolvers" -> ListValue(queryVal.fieldData.asScala.toVector))
        )): Value))

  case class QueryTrace(
      startTime: Instant,
      startNanos: Long,
      fieldData: ConcurrentLinkedQueue[Value])
}
