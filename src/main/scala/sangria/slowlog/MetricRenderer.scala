package sangria.slowlog

import java.util.concurrent.TimeUnit

import com.codahale.metrics.Snapshot
import sangria.marshalling.InputUnmarshaller

trait MetricRenderer {
  def renderField(typeName: String, metrics: FieldMetrics): String
  def renderVariables[In : InputUnmarshaller](variables: In, names: Vector[String]): String
  def renderExecution(durationNanos: Long, validationNanos: Long, queryReducerNanos: Long): String
  def renderDuration(durationNanos: Long): String
}

object MetricRenderer {
  implicit val default = new DefaultMetricRenderer(TimeUnit.MILLISECONDS)
}

class DefaultMetricRenderer(val unit: TimeUnit) extends MetricRenderer {
  def renderVariables[In : InputUnmarshaller](variables: In, names: Vector[String]) = {
    val iu = implicitly[InputUnmarshaller[In]]
    val renderedVars = names.flatMap(name ⇒ iu.getRootMapValue(variables, name).map(v ⇒ s"  $$${name} = ${iu.render(v)}"))

    if (renderedVars.nonEmpty)
      renderedVars mkString "\n"
    else
      ""
  }

  def renderField(typeName: String, metrics: FieldMetrics) = {
    val success = metrics.success.getCount
    val failure = metrics.failure.getCount
    val histogram = metrics.histogram.getSnapshot
    val count = metrics.histogram.getCount

    val countStr = s"[$typeName] count: $success${if (failure > 0) "/" + failure else ""}"

    (countStr +: renderHistogram(count, histogram, unit).map{case (n, v) ⇒ s"$n: $v"}).mkString(", ")
  }

  def renderExecution(durationNanos: Long, validationNanos: Long, queryReducerNanos: Long) =
    s"[Execution Metrics] duration: ${renderDuration(durationNanos)}, validation: ${renderDuration(validationNanos)}, reducers: ${renderDuration(queryReducerNanos)}"

  def renderHistogram(count: Long, snap: Snapshot, unit: TimeUnit): Vector[(String, String)] =
    if (count == 1)
      Vector("time" → renderDuration(snap.getMax))
    else
      Vector(
        "min" → renderDuration(snap.getMin),
        "max" → renderDuration(snap.getMax),
        "mean" → renderDuration(snap.getMean.toLong),
        "p75" → renderDuration(snap.get75thPercentile.toLong),
        "p95" → renderDuration(snap.get95thPercentile.toLong),
        "p99" → renderDuration(snap.get99thPercentile.toLong))

  def renderDuration(durationNanos: Long) =
    if (unit == TimeUnit.NANOSECONDS) durationNanos + renderTimeUnit(unit)
    else unit.convert(durationNanos, TimeUnit.NANOSECONDS) + renderTimeUnit(unit)

  def renderTimeUnit(unit: TimeUnit) = unit match  {
    case TimeUnit.DAYS ⇒ "d"
    case TimeUnit.HOURS ⇒ "h"
    case TimeUnit.MICROSECONDS ⇒ "μs"
    case TimeUnit.MILLISECONDS ⇒ "ms"
    case TimeUnit.MINUTES ⇒ "m"
    case TimeUnit.NANOSECONDS ⇒ "ns"
    case TimeUnit.SECONDS ⇒ "s"
  }
}
