package sangria.slowlog

import com.codahale.metrics.{Counter, Histogram}

case class FieldMetrics(success: Counter, failure: Counter, histogram: Histogram) {
  lazy val snapshot = histogram.getSnapshot
  lazy val count = histogram.getCount
}
