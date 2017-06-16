package org.sangria.slowlog

import com.codahale.metrics.{Counter, Histogram}

case class FieldMetrics(success: Counter, failure: Counter, histogram: Histogram)
