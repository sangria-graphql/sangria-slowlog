package sangria.slowlog

import java.util.concurrent.TimeUnit

import com.codahale.metrics.Snapshot
import sangria.marshalling.InputUnmarshaller
import sangria.ast

trait MetricRenderer {
  def renderField(typeName: String, metrics: FieldMetrics, prefix: String): String
  def renderVariables[In : InputUnmarshaller](variables: In, names: Vector[String]): String
  def renderExecution(durationNanos: Long, validationNanos: Long, queryReducerNanos: Long): String
  def renderDuration(durationNanos: Long): String
  def durationField(name: String, value: Long): ast.ObjectField
  def renderLogMessage(durationNanos: Long, enrichedQuery: String): String
  def fieldMetrics(metrics: FieldMetrics): ast.Value
}

object MetricRenderer {
  implicit val default = new DefaultMetricRenderer(TimeUnit.MILLISECONDS)
  
  def in(unit: TimeUnit) = new DefaultMetricRenderer(unit)
}

class DefaultMetricRenderer(val unit: TimeUnit) extends MetricRenderer {
  def renderVariables[In : InputUnmarshaller](variables: In, names: Vector[String]) = {
    val iu = implicitly[InputUnmarshaller[In]]
    val renderedVars = names.flatMap(name => iu.getRootMapValue(variables, name).map(v => s"  $$$name = ${iu.render(v)}"))

    if (renderedVars.nonEmpty)
      renderedVars mkString "\n"
    else
      ""
  }

  def renderField(typeName: String, metrics: FieldMetrics, prefix: String) = {
    val success = metrics.success.getCount
    val failure = metrics.failure.getCount
    val histogram = metrics.snapshot
    val count = metrics.count

    val countStr = s"[$prefix$typeName] count: $success${if (failure > 0) "/" + failure else ""}"

    (countStr +: renderHistogram(count, histogram, unit).map{case (n, v) => s"$n: $v"}).mkString(", ")
  }

  def renderExecution(durationNanos: Long, validationNanos: Long, queryReducerNanos: Long) =
    s"[Execution Metrics] duration: ${renderDuration(durationNanos)}, validation: ${renderDuration(validationNanos)}, reducers: ${renderDuration(queryReducerNanos)}"

  def renderHistogram(count: Long, snap: Snapshot, unit: TimeUnit): Vector[(String, String)] =
    if (count == 1)
      Vector("time" -> renderDuration(snap.getMax))
    else
      Vector(
        "min" -> renderDuration(snap.getMin),
        "max" -> renderDuration(snap.getMax),
        "mean" -> renderDuration(snap.getMean.toLong),
        "p75" -> renderDuration(snap.get75thPercentile.toLong),
        "p95" -> renderDuration(snap.get95thPercentile.toLong),
        "p99" -> renderDuration(snap.get99thPercentile.toLong))

  def renderDuration(durationNanos: Long) =
    if (unit == TimeUnit.NANOSECONDS) durationNanos + renderTimeUnit(unit)
    else unit.convert(durationNanos, TimeUnit.NANOSECONDS) + renderTimeUnit(unit)

  def renderLogMessage(durationNanos: Long, enrichedQuery: String) =
    s"Slow GraphQL query [${renderDuration(durationNanos)}].\n\n$enrichedQuery"


  def fieldMetrics(metrics: FieldMetrics) = {
    val durationMetrics =
      Vector(
        durationField("min", metrics.snapshot.getMin),
        durationField("max", metrics.snapshot.getMax),
        durationField("mean", metrics.snapshot.getMean.toLong),
        durationField("p75", metrics.snapshot.get75thPercentile.toLong),
        durationField("p95", metrics.snapshot.get95thPercentile.toLong),
        durationField("p99", metrics.snapshot.get99thPercentile.toLong))

    val failed =
      if (metrics.failure.getCount > 0)
        Vector(ast.ObjectField("failed", ast.BigIntValue(metrics.failure.getCount)))
      else
        Vector.empty

    ast.ObjectValue(
      ast.ObjectField("count", ast.BigIntValue(metrics.success.getCount)) +: (failed ++ durationMetrics))
  }

  def durationField(name: String, value: Long): ast.ObjectField = {
    val correctValue =
      if (unit == TimeUnit.NANOSECONDS) value
      else unit.convert(value, TimeUnit.NANOSECONDS)

    ast.ObjectField(name + timeUnitSuffix, ast.BigIntValue(correctValue))
  }


  def renderTimeUnit(unit: TimeUnit) = unit match  {
    case TimeUnit.DAYS => "d"
    case TimeUnit.HOURS => "h"
    case TimeUnit.MICROSECONDS => "μs"
    case TimeUnit.MILLISECONDS => "ms"
    case TimeUnit.MINUTES => "m"
    case TimeUnit.NANOSECONDS => "ns"
    case TimeUnit.SECONDS => "s"
  }

  lazy val timeUnitSuffix = unit match  {
    case TimeUnit.DAYS => "Day"
    case TimeUnit.HOURS => "Hour"
    case TimeUnit.MICROSECONDS => "Micros"
    case TimeUnit.MILLISECONDS => "Ms"
    case TimeUnit.MINUTES => "Min"
    case TimeUnit.NANOSECONDS => "Ns"
    case TimeUnit.SECONDS => "Sec"
  }
}
