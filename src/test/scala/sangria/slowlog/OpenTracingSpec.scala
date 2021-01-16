package sangria.slowlog

import org.json4s.JsonAST._
import org.scalatest.{BeforeAndAfter, OptionValues}
import sangria.execution.Executor
import sangria.macros._
import sangria.marshalling.ScalaInput
import sangria.marshalling.json4s.native._
import sangria.slowlog.util.{FutureResultSupport, StringMatchers}
import io.opentracing.mock.{MockSpan, MockTracer}
import io.opentracing.mock.MockTracer.Propagator
import io.opentracing.util.ThreadLocalScopeManager
import io.opentracing.contrib.concurrent.TracedExecutionContext

import scala.concurrent.ExecutionContext.global
import scala.language.postfixOps
import scala.jdk.CollectionConverters._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final case class SimpleMockSpan(traceId: Long, spanId: Long, parentId: Long, operationName: String)
object SimpleMockSpan {
  def apply(s: MockSpan): SimpleMockSpan =
    SimpleMockSpan(s.context().traceId(), s.context().spanId(), s.parentId(), s.operationName())
}

class OpenTracingSpec
    extends AnyWordSpec
    with Matchers
    with FutureResultSupport
    with StringMatchers
    with OptionValues
    with BeforeAndAfter {
  import TestSchema._

  implicit val mockTracer = new MockTracer(new ThreadLocalScopeManager, Propagator.TEXT_MAP)
  implicit val ec = new TracedExecutionContext(global, mockTracer, false)

  before {
    mockTracer.reset()
  }

  val mainQuery =
    gql"""
      query Foo {
        friends {
          ...Name
          ...Name2
        }
      }

      query Test($$limit: Int!) {
        __typename
        name
             ...Name1
             pets(limit: $$limit) {
          ... on Cat {
            name
            meows
            ...Name
          }
          ... on Dog {
            ...Name1
            ...Name1
            foo: name
            barks
          }
        }
      }

      fragment Name on Named {
        name
        ...Name1
      }

      fragment Name1 on Named {
        ... on Person {
          name
        }
      }

      fragment Name2 on Named {
        name
      }
    """

  "OpenTracing middleware" should {
    "Nest the spans correctly" in {
      val vars = ScalaInput.scalaInput(Map("limit" -> 4))

      val spanBuilder = mockTracer.buildSpan("root")
      val span = spanBuilder.start()
      val scope = mockTracer.activateSpan(span)

      Executor
        .execute(
          schema,
          mainQuery,
          root = bob,
          operationName = Some("Test"),
          variables = vars,
          middleware = SlowLog.openTracing() :: Nil)
        .await

      mockTracer.activeSpan().finish()

      val finishedSpans = mockTracer.finishedSpans.asScala.map(SimpleMockSpan.apply).toSet
      finishedSpans.forall(_.traceId == 1) shouldBe true

      val querySpan = finishedSpans.find(_.operationName == "Test").get
      querySpan.parentId shouldEqual 2

      val typeNameSpan = finishedSpans.find(_.operationName == "__typename").get
      typeNameSpan.parentId shouldEqual querySpan.spanId

      val bobSpan =
        finishedSpans.filter(s => s.operationName == "name" && s.parentId == querySpan.spanId)
      bobSpan.size shouldBe 1

      val petsSpan =
        finishedSpans.filter(s => s.operationName == "pets" && s.parentId == querySpan.spanId)
      petsSpan.size shouldBe 1

      val petsNameSpan =
        finishedSpans.filter(s => s.operationName == "name" && s.parentId == petsSpan.head.spanId)
      petsNameSpan.size shouldBe 4

      val petsMeowsSpan =
        finishedSpans.filter(s => s.operationName == "meows" && s.parentId == petsSpan.head.spanId)
      petsMeowsSpan.size shouldBe 4
    }
  }

  def removeTime(res: JValue) =
    res.transformField {
      case (name @ "startOffset", _) => name -> JInt(0)
      case (name @ "duration", _) => name -> JInt(0)
      case (name @ "startTime", _) => name -> JString("DATE")
      case (name @ "endTime", _) => name -> JString("DATE")
      case (name @ "resolvers", JArray(elems)) =>
        name -> JArray(elems.sortBy(e => (e \ "path").toString))
    }
}
