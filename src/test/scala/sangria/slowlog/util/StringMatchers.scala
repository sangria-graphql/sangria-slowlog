package sangria.slowlog.util

import org.scalactic.AbstractStringUniformity

trait StringMatchers {
  def strippedOfCarriageReturns: AbstractStringUniformity = (s: String) => stripCarriageReturns(s)

  def stripCarriageReturns(s: String): String = s.replaceAll("\\r", "")
}
