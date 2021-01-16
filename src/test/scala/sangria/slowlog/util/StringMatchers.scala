package sangria.slowlog.util

import org.scalactic.AbstractStringUniformity

trait StringMatchers {
  def strippedOfCarriageReturns: AbstractStringUniformity = new AbstractStringUniformity {
    def normalized(s: String): String = stripCarriageReturns(s)
  }

  def stripCarriageReturns(s: String): String = s.replaceAll("\\r", "")
}
