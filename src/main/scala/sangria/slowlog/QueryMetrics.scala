package sangria.slowlog

import com.codahale.metrics.{Counter, ExponentiallyDecayingReservoir, Histogram}
import sangria.ast
import sangria.ast.{AstVisitor, FragmentSpread}
import sangria.execution.Extension
import sangria.marshalling.InputUnmarshaller
import sangria.schema.{ObjectType, Schema}
import sangria.visitor.VisitorCommand

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import sangria.marshalling.queryAst._
import sangria.validation.TypeInfo

import scala.collection.immutable.VectorBuilder

case class QueryMetrics(
    pathData: TrieMap[Vector[String], TrieMap[String, FieldMetrics]],
    fieldData: TrieMap[String, TrieMap[String, FieldMetrics]],
    startNanos: Long,
    collectFieldData: Boolean
) {
  def update(
      path: Vector[String],
      typeName: String,
      fieldName: String,
      success: Boolean,
      startNanos: Long,
      endNanos: Long) = {
    val duration = endNanos - startNanos
    val forPath = pathData.getOrElseUpdate(path, TrieMap.empty)
    val m = forPath.getOrElseUpdate(
      typeName,
      FieldMetrics(new Counter, new Counter, new Histogram(new ExponentiallyDecayingReservoir)))

    if (success) m.success.inc()
    else m.failure.inc()

    m.histogram.update(duration)

    if (collectFieldData) {
      val forType = fieldData.getOrElseUpdate(typeName, TrieMap.empty)
      val fm = forType.getOrElseUpdate(
        fieldName,
        FieldMetrics(new Counter, new Counter, new Histogram(new ExponentiallyDecayingReservoir)))

      if (success) fm.success.inc()
      else fm.failure.inc()

      fm.histogram.update(duration)
    }
  }

  def enrichQuery[In: InputUnmarshaller](
      schema: Schema[_, _],
      query: ast.Document,
      operationName: Option[String],
      variables: In,
      durationNanos: Long,
      validationNanos: Long,
      queryReducerNanos: Long
  )(implicit renderer: MetricRenderer): ast.Document = {
    val fragmentPaths = collectFragmentPaths(schema, query, operationName)
    var inOperation = false
    var inFragment: Option[String] = None

    AstVisitor.visitAstWithTypeInfo(schema, query) { typeInfo =>
      AstVisitor(
        onEnter = {
          case op: ast.OperationDefinition
              if query.operations.size == 1 || op.name == operationName =>
            inOperation = true

            val varNames = op.variables.map(_.name)

            val varComments =
              if (varNames.nonEmpty) {
                val rendered = renderer.renderVariables(variables, varNames)

                if (rendered.trim.nonEmpty) toComments("\n" + rendered)
                else Vector.empty
              } else Vector.empty

            val execution =
              toComments(
                renderer.renderExecution(durationNanos, validationNanos, queryReducerNanos))

            VisitorCommand.Transform(
              op.copy(comments = addComments(op.comments, execution ++ varComments)))

          case _: ast.OperationDefinition =>
            inOperation = true
            VisitorCommand.Continue

          case fd: ast.FragmentDefinition =>
            inFragment = Some(fd.name)

            val originPaths = fragmentPaths.getOrElse(fd.name, Vector.empty)

            if (originPaths.nonEmpty) {
              val paths =
                originPaths.iterator.map {
                  case (_, hoPath) if hoPath.isEmpty => "* (query root)"
                  case (_, hoPath) => "* " + hoPath.mkString(".")
                }

              val usages =
                toComments(s"[usages]\n${paths.mkString("\n")}")

              VisitorCommand.Transform(fd.copy(comments = addComments(fd.comments, usages)))
            } else VisitorCommand.Continue

          case a: ast.Field if inOperation =>
            val path =
              typeInfo.ancestors.reverseIterator.collect { case f: ast.Field =>
                f.outputName
              }.toVector
            val varComments = variableComments(a, variables)
            val pathComments =
              for {
                typeMetrics <- pathData.get(path)
              } yield VisitorCommand.Transform(
                a.copy(comments =
                  addComments(a.comments, metricComments(typeInfo, typeMetrics) ++ varComments)))

            pathComments.getOrElse(VisitorCommand.Continue)

          case a: ast.Field if inFragment.isDefined =>
            val fragmentName = inFragment.get
            val path =
              typeInfo.ancestors.reverseIterator.collect { case f: ast.Field =>
                f.outputName
              }.toVector
            val varComments = variableComments(a, variables)
            val originPaths = fragmentPaths.getOrElse(fragmentName, Vector.empty)

            val pathComments =
              originPaths.flatMap { case (oPath, hoPath) =>
                pathData.get(oPath ++ path) match {
                  case Some(typeMetrics) =>
                    metricComments(
                      typeInfo,
                      typeMetrics,
                      hoPath.mkString("", ".", if (hoPath.nonEmpty) "." else "") + path.mkString(
                        "",
                        ".",
                        " "))
                  case None => Vector.empty
                }
              }

            if (pathComments.nonEmpty)
              VisitorCommand.Transform(
                a.copy(comments = addComments(a.comments, pathComments ++ varComments)))
            else
              VisitorCommand.Continue
        },
        onLeave = {
          case _: ast.OperationDefinition =>
            inOperation = false
            VisitorCommand.Continue
          case _: ast.FragmentDefinition =>
            inFragment = None
            VisitorCommand.Continue
        }
      )
    }
  }

  private def metricComments(
      typeInfo: TypeInfo,
      typeMetrics: TrieMap[String, FieldMetrics],
      prefix: String = "")(implicit renderer: MetricRenderer): Vector[ast.Comment] = {
    val previousParentType = typeInfo.previousParentType
    val rendered =
      for {
        parentType <- previousParentType
        fieldMetrics <- typeMetrics.get(parentType.name)
      } yield renderer.renderField(parentType.name, fieldMetrics, prefix)

    rendered match {
      case Some(text) =>
        toComments(text)
      case None
          if typeMetrics.isEmpty || previousParentType.isDefined && previousParentType.get
            .isInstanceOf[ObjectType[_, _]] =>
        Vector.empty[ast.Comment]
      case None if typeMetrics.size == 1 =>
        toComments(renderer.renderField(typeMetrics.head._1, typeMetrics.head._2, prefix))
      case None =>
        typeMetrics.iterator.flatMap { case (typeName, metrics) =>
          toComments(renderer.renderField(typeName, metrics, prefix))
        }.toVector
    }
  }

  private def variableComments[In: InputUnmarshaller](field: ast.Field, variables: In)(implicit
      renderer: MetricRenderer): Vector[ast.Comment] = {
    val varNames = field.arguments.flatMap(findVariableNames)

    if (varNames.nonEmpty) {
      val renderedVars = renderer.renderVariables(variables, varNames)

      if (renderedVars.nonEmpty) toComments("\n" + renderedVars)
      else Vector.empty
    } else Vector.empty
  }

  private def collectFragmentPaths(
      schema: Schema[_, _],
      query: ast.Document,
      operationName: Option[String])
      : mutable.Map[String, Vector[(scala.Vector[String], scala.Vector[String])]] = {
    val fragmentPaths = mutable.Map[String, mutable.Set[(String, Vector[String])]]()

    def fragmentCollector(sourceName: String, typeInfo: TypeInfo) = AstVisitor {
      case sp: FragmentSpread =>
        val dstSet = fragmentPaths.getOrElseUpdate(sp.name, mutable.Set[(String, Vector[String])]())
        val path = typeInfo.ancestors.reverseIterator.collect { case f: ast.Field =>
          f.outputName
        }.toVector

        dstSet += sourceName -> path

        VisitorCommand.Continue
    }

    // collect of fragment relations

    query.fragments.foreach { case (sourceName, fragment) =>
      AstVisitor.visitAstWithTypeInfo(schema, fragment)(typeInfo =>
        fragmentCollector(sourceName, typeInfo))
    }

    query
      .operation(operationName)
      .foreach(op =>
        AstVisitor.visitAstWithTypeInfo(schema, op)(typeInfo => fragmentCollector("", typeInfo)))

    // expand all intermediate fragments

    fragmentPaths.map { case (fragName, path) =>
      def loop(name: String, p: Vector[String]): Vector[(Vector[String], Vector[String])] =
        fragmentPaths.get(name) match {
          case Some(b) =>
            b.toVector.flatMap { case (n, cp) =>
              loop(n, cp).map(r => (r._1 ++ p, r._2 ++ Vector(s"($name)") ++ p))
            }
          case None =>
            Vector(p -> p)
        }

      fragName -> removeDuplicates(path.iterator.flatMap { case (n, cp) => loop(n, cp) }.toVector)
    }
  }

  private def removeDuplicates(data: Vector[(Vector[String], Vector[String])]) = {
    val seen = mutable.Set[Vector[String]]()
    val builder = new VectorBuilder[(Vector[String], Vector[String])]

    data.foreach { d =>
      if (!seen.contains(d._1)) {
        seen += d._1
        builder += d
      }
    }

    builder.result()
  }

  def findVariableNames(node: ast.AstNode) = {
    val names = new mutable.HashSet[String]

    AstVisitor.visit(
      node,
      AstVisitor.simple { case vv: ast.VariableValue =>
        names += vv.name
      })

    names.toVector
  }

  def addComments(existing: Vector[ast.Comment], added: Vector[ast.Comment]) =
    if (existing.nonEmpty) (existing :+ ast.Comment("")) ++ added
    else added

  def toComments(s: String): Vector[ast.Comment] =
    s.split("\n").iterator.map(c => ast.Comment(c.trim)).toVector

  def extension(
      enrichedQuery: ast.Document,
      durationNanos: Long,
      validationNanos: Long,
      queryReducerNanos: Long
  )(implicit renderer: MetricRenderer): Extension[ast.Value] = {
    val sortedTypes =
      fieldData.iterator
        .map { case (typeName, fields) =>
          typeName -> fields.map { case (fieldName, metrics) =>
            metrics.snapshot.get98thPercentile()
          }.max
        }
        .toVector
        .sortBy(_._2)(Ordering[Double].reverse)

    val typeMetrics =
      sortedTypes.map { case (typeName, _) =>
        val fields =
          fieldData(typeName).toVector
            .sortBy(_._2.snapshot.get98thPercentile())(Ordering[Double].reverse)
            .map { case (fieldName, metrics) =>
              ast.ObjectField(fieldName, renderer.fieldMetrics(metrics))
            }

        ast.ObjectField(typeName, ast.ObjectValue(fields))
      }

    val root =
      ast.ObjectValue(
        Vector(
          ast.ObjectField(
            "metrics",
            ast.ObjectValue(Vector(
              renderer.durationField("execution", durationNanos),
              renderer.durationField("validation", validationNanos),
              renderer.durationField("reducers", queryReducerNanos),
              ast.ObjectField("query", ast.StringValue(enrichedQuery.renderPretty)),
              ast.ObjectField("types", ast.ObjectValue(typeMetrics))
            ))
          )))

    Extension(root: ast.Value)
  }

}
