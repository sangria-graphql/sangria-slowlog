package sangria.slowlog

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{Counter, ExponentiallyDecayingReservoir, Histogram}
import sangria.ast
import sangria.ast.AstVisitor
import sangria.marshalling.InputUnmarshaller
import sangria.schema.{ObjectType, Schema}
import sangria.visitor.VisitorCommand

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

case class QueryMetrics(
  pathData: TrieMap[Vector[String], TrieMap[String, FieldMetrics]],
  fieldData: TrieMap[String, TrieMap[String, FieldMetrics]],
  startNanos: Long,
  collectFieldData: Boolean
) {
  def update(path: Vector[String], typeName: String, fieldName: String, success: Boolean, startNanos: Long, endNanos: Long) = {
    val duration = endNanos - startNanos
    val forPath = pathData.getOrElseUpdate(path, TrieMap.empty)
    val m = forPath.getOrElseUpdate(typeName, FieldMetrics(
      new Counter,
      new Counter,
      new Histogram(new ExponentiallyDecayingReservoir)))

    if (success) m.success.inc()
    else m.failure.inc()

    m.histogram.update(duration)

    if (collectFieldData) {
      val forType = fieldData.getOrElseUpdate(typeName, TrieMap.empty)
      val fm = forType.getOrElseUpdate(fieldName, FieldMetrics(
        new Counter,
        new Counter,
        new Histogram(new ExponentiallyDecayingReservoir)))

      if (success) fm.success.inc()
      else fm.failure.inc()

      fm.histogram.update(duration)
    }
  }

  def enrichQuery[In : InputUnmarshaller](
    schema: Schema[_, _],
    query: ast.Document,
    operationName: Option[String],
    variables: In,
    durationNanos: Long,
    validationNanos: Long,
    queryReducerNanos: Long
  )(implicit renderer: MetricRenderer): ast.Document = {
    var inOperation = false
    AstVisitor.visitAstWithTypeInfo(schema, query) { typeInfo ⇒
      AstVisitor(
        onEnter = {
          case op: ast.OperationDefinition if query.operations.size == 1 || op.name == operationName ⇒
            inOperation = true

            val varNames = op.variables.map(_.name)

            val varComments =
              if (varNames.nonEmpty) {
                val rendered = renderer.renderVariables(variables, varNames)

                if (rendered.trim.nonEmpty) toComments("\n" + rendered)
                else Vector.empty
              } else Vector.empty

            val execution =
              toComments(renderer.renderExecution(durationNanos, validationNanos, queryReducerNanos))

            VisitorCommand.Transform(op.copy(comments = addComments(op.comments, execution ++ varComments)))

          case op: ast.OperationDefinition ⇒
            inOperation = true
            VisitorCommand.Continue

          case a: ast.Field if inOperation ⇒
            val path = typeInfo.ancestors.collect { case f: ast.Field ⇒ f.outputName }.reverse.toVector

            val varNames = a.arguments.flatMap(findVariableNames)

            val varComments =
              if (varNames.nonEmpty) {
                val renderedVars = renderer.renderVariables(variables, varNames)

                if (renderedVars.nonEmpty) toComments("\n" + renderedVars)
                else Vector.empty
              } else Vector.empty

            pathData.get(path) match {
              case Some(typeMetrics) ⇒
                val rendered =
                  for {
                    parentType ← typeInfo.previousParentType
                    fieldMetrics ← typeMetrics.get(parentType.name)
                  } yield renderer.renderField(parentType.name, fieldMetrics)

                val debugComments =
                  rendered match {
                    case Some(text) ⇒
                      toComments(text)
                    case None if typeMetrics.isEmpty || typeInfo.previousParentType.isDefined && typeInfo.previousParentType.get.isInstanceOf[ObjectType[_, _]] ⇒
                      Vector.empty[ast.Comment]
                    case None if typeMetrics.size == 1 ⇒
                      toComments(renderer.renderField(typeMetrics.head._1, typeMetrics.head._2))
                    case None ⇒
                      typeMetrics.flatMap { case (typeName, metrics) ⇒
                        toComments(renderer.renderField(typeName, metrics))
                      }.drop(1).toVector
                  }

                VisitorCommand.Transform(a.copy(comments = addComments(a.comments, debugComments ++ varComments)))

              case None ⇒
                VisitorCommand.Continue
            }
        },
        onLeave = {
          case op: ast.OperationDefinition ⇒
            inOperation = false
            VisitorCommand.Continue
        })
    }
  }

  def findVariableNames(node: ast.AstNode) = {
    val names = new mutable.HashSet[String]

    AstVisitor.visit(node, AstVisitor.simple {
      case vv: ast.VariableValue ⇒ names += vv.name
    })

    names.toVector
  }


  def addComments(existing: Vector[ast.Comment], added: Vector[ast.Comment]) =
    if (existing.nonEmpty) (existing :+ ast.Comment("")) ++ added
    else added

  def toComments(s: String): Vector[ast.Comment] = s.split("\n").map(c ⇒ ast.Comment(c.trim)).toVector
}