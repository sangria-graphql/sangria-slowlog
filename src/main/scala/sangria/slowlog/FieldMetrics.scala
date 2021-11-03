package sangria.slowlog

import com.codahale.metrics.{Counter, Histogram, Snapshot}

case class FieldMetrics(success: Counter, failure: Counter, histogram: Histogram) {
  lazy val snapshot: Snapshot = histogram.getSnapshot
  lazy val count: Long = histogram.getCount
}
