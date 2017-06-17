package sangria.slowlog

import java.util.concurrent.TimeUnit

import org.json4s.JsonAST.{JInt, JString, JValue}

import language.postfixOps
import sangria.slowlog.util.FutureResultSupport
import org.scalatest.{Matchers, OptionValues, WordSpec}
import sangria.execution._
import sangria.schema._
import sangria.macros._
import sangria.marshalling.ScalaInput

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import sangria.marshalling.json4s.native._
import org.json4s.native.JsonMethods._
import sangria.slowlog.util._

class SlowLogSpec extends WordSpec with Matchers with FutureResultSupport with StringMatchers with OptionValues {
  trait Named {
    def name: Option[String]
  }

  case class Dog(name: Option[String], barks: Option[Boolean]) extends Named
  case class Cat(name: Option[String], meows: Option[Boolean]) extends Named
  case class Person(name: Option[String], pets: Option[List[Option[Any]]], friends: Option[List[Option[Named]]]) extends Named

  val NamedType = InterfaceType("Named", fields[Unit, Named](
    Field("name", OptionType(StringType), resolve = _.value.name)))

  val DogType = ObjectType("Dog", interfaces[Unit, Dog](NamedType), fields[Unit, Dog](
    Field("name", OptionType(StringType), resolve = _.value.name),
    Field("barks", OptionType(BooleanType), resolve = _.value.barks)))

  val CatType = ObjectType("Cat", interfaces[Unit, Cat](NamedType), fields[Unit, Cat](
    Field("name", OptionType(StringType), resolve = c ⇒ {
      Future {
        Thread.sleep((math.random * 10).toLong)
        c.value.name
      }

    }),
    Field("meows", OptionType(BooleanType), resolve = _.value.meows)))

  val PetType = UnionType[Unit]("Pet", types = DogType :: CatType :: Nil)

  val LimitArg = Argument("limit", OptionInputType(IntType), 10)

  val PersonType = ObjectType("Person", interfaces[Unit, Person](NamedType), fields[Unit, Person](
    Field("pets", OptionType(ListType(OptionType(PetType))),
      arguments = LimitArg :: Nil,
      resolve = c ⇒ c.withArgs(LimitArg)(limit ⇒ c.value.pets.map(_.take(limit)))),
    Field("favouritePet", PetType, resolve = _.value.pets.flatMap(_.headOption.flatMap(identity)).get),
    Field("favouritePetList", ListType(PetType), resolve = _.value.pets.getOrElse(Nil).flatMap(x ⇒ x).toSeq),
    Field("favouritePetOpt", OptionType(PetType), resolve = _.value.pets.flatMap(_.headOption.flatMap(identity))),
    Field("friends", OptionType(ListType(OptionType(NamedType))), resolve = _.value.friends)))

  val TestSchema = Schema(PersonType)

  val garfield = Cat(Some("Garfield"), Some(false))
  val odie = Dog(Some("Odie"), Some(true))
  val liz = Person(Some("Liz"), None, None)
  val bob = Person(Some("Bob"), Some(Iterator.continually(Some(garfield)).take(20).toList :+ Some(odie)), Some(List(Some(liz), Some(odie))))

  val schema = Schema(PersonType)

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

  "SlowLog" should {
    "enrich the query and separate operation" in {
      val vars = ScalaInput.scalaInput(Map("limit" → 30))
      var enrichedQuery: Option[String] = None

      Executor.execute(schema, mainQuery,
        root = bob,
        operationName = Some("Test"),
        variables = vars,
        middleware = SlowLog.log((_, query) ⇒ enrichedQuery = Some(query), 0 seconds) :: Nil).await

      removeTime(enrichedQuery.value, "ms") should equal (
        """# [Execution Metrics] duration: 0ms, validation: 0ms, reducers: 0ms
          |#
          |# $limit = 30
          |query Test($limit: Int!) {
          |  # [Person] count: 1, time: 0ms
          |  __typename
          |
          |  # [Person] count: 1, time: 0ms
          |  name
          |  ...Name1
          |
          |  # [Person] count: 1, time: 0ms
          |  #
          |  # $limit = 30
          |  pets(limit: $limit) {
          |    ... on Cat {
          |      # [Cat] count: 20, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
          |      name
          |
          |      # [Cat] count: 20, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
          |      meows
          |      ...Name
          |    }
          |    ... on Dog {
          |      ...Name1
          |      ...Name1
          |
          |      # [Dog] count: 1, time: 0ms
          |      foo: name
          |
          |      # [Dog] count: 1, time: 0ms
          |      barks
          |    }
          |  }
          |}
          |
          |# [usages]
          |# * pets
          |fragment Name on Named {
          |  # [pets.name Cat] count: 20, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
          |  name
          |  ...Name1
          |}
          |
          |# [usages]
          |# * (query root)
          |# * pets.(Name)
          |fragment Name1 on Named {
          |  ... on Person {
          |    # [name Person] count: 1, time: 0ms
          |    name
          |  }
          |}""".stripMargin) (after being strippedOfCarriageReturns)
    }

    "enrich only relevant parts of the query" in {
      val vars = ScalaInput.scalaInput(Map("limit" → 30))
      var enrichedQuery: Option[String] = None

      Executor.execute(schema, mainQuery,
        root = bob,
        operationName = Some("Test"),
        variables = vars,
        middleware = SlowLog.log((_, query) ⇒ enrichedQuery = Some(query), 0 seconds, separateOp = false) :: Nil).await
      
      removeTime(enrichedQuery.value, "ms") should equal (
        """query Foo {
          |  friends {
          |    ...Name
          |    ...Name2
          |  }
          |}
          |
          |# [Execution Metrics] duration: 0ms, validation: 0ms, reducers: 0ms
          |#
          |# $limit = 30
          |query Test($limit: Int!) {
          |  # [Person] count: 1, time: 0ms
          |  __typename
          |
          |  # [Person] count: 1, time: 0ms
          |  name
          |  ...Name1
          |
          |  # [Person] count: 1, time: 0ms
          |  #
          |  # $limit = 30
          |  pets(limit: $limit) {
          |    ... on Cat {
          |      # [Cat] count: 20, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
          |      name
          |
          |      # [Cat] count: 20, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
          |      meows
          |      ...Name
          |    }
          |    ... on Dog {
          |      ...Name1
          |      ...Name1
          |
          |      # [Dog] count: 1, time: 0ms
          |      foo: name
          |
          |      # [Dog] count: 1, time: 0ms
          |      barks
          |    }
          |  }
          |}
          |
          |# [usages]
          |# * pets
          |fragment Name on Named {
          |  # [pets.name Cat] count: 20, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
          |  name
          |  ...Name1
          |}
          |
          |# [usages]
          |# * (query root)
          |# * pets.(Name)
          |fragment Name1 on Named {
          |  ... on Person {
          |    # [name Person] count: 1, time: 0ms
          |    name
          |  }
          |}
          |
          |fragment Name2 on Named {
          |  name
          |}""".stripMargin) (after being strippedOfCarriageReturns)
    }

    "use different time units" in {
      val vars = ScalaInput.scalaInput(Map("limit" → 10))
      var enrichedQuery: Option[String] = None

      implicit val renderer = MetricRenderer.in(TimeUnit.SECONDS)

      Executor.execute(schema,
        gql"""
          {
            # test comment
            name
          }
        """,
        root = bob,
        variables = vars,
        middleware = SlowLog.log((_, query) ⇒ enrichedQuery = Some(query), 0 seconds, separateOp = false) :: Nil).await

      removeTime(enrichedQuery.value, "s") should equal (
        """# [Execution Metrics] duration: 0s, validation: 0s, reducers: 0s
          |{
          |  # test comment
          |  #
          |  # [Person] count: 1, time: 0s
          |  name
          |}""".stripMargin) (after being strippedOfCarriageReturns)
    }

    "add extensions" in {
      val vars = ScalaInput.scalaInput(Map("limit" → 3))

      val res = Executor.execute(schema, mainQuery,
        root = bob,
        operationName = Some("Test"),
        variables = vars,
        middleware = SlowLog.extension :: Nil).await

      removeTime(res, "ms", "Ms") should be (parse(
        """{
          |  "data": {
          |    "__typename": "Person",
          |    "name": "Bob",
          |    "pets": [
          |      {
          |        "name": "Garfield",
          |        "meows": false
          |      },
          |      {
          |        "name": "Garfield",
          |        "meows": false
          |      },
          |      {
          |        "name": "Garfield",
          |        "meows": false
          |      }
          |    ]
          |  },
          |  "extensions": {
          |    "metrics": {
          |      "executionMs": 0,
          |      "validationMs": 0,
          |      "reducersMs": 0,
          |      "query": "query Foo {\n  friends {\n    ...Name\n    ...Name2\n  }\n}\n\n# [Execution Metrics] duration: 0ms, validation: 0ms, reducers: 0ms\n#\n# $limit = 3\nquery Test($limit: Int!) {\n  # [Person] count: 1, time: 0ms\n  __typename\n\n  # [Person] count: 1, time: 0ms\n  name\n  ...Name1\n\n  # [Person] count: 1, time: 0ms\n  #\n  # $limit = 3\n  pets(limit: $limit) {\n    ... on Cat {\n      # [Cat] count: 3, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms\n      name\n\n      # [Cat] count: 3, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms\n      meows\n      ...Name\n    }\n    ... on Dog {\n      ...Name1\n      ...Name1\n      foo: name\n      barks\n    }\n  }\n}\n\n# [usages]\n# * pets\nfragment Name on Named {\n  # [pets.name Cat] count: 3, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms\n  name\n  ...Name1\n}\n\n# [usages]\n# * (query root)\n# * pets.(Name)\nfragment Name1 on Named {\n  ... on Person {\n    # [name Person] count: 1, time: 0ms\n    name\n  }\n}\n\nfragment Name2 on Named {\n  name\n}",
          |      "types": {
          |        "Cat": {
          |          "name": {"count": 3, "minMs": 0, "maxMs": 0, "meanMs": 0, "p75Ms": 0, "p95Ms": 0, "p99Ms": 0},
          |          "meows": {"count": 3, "minMs": 0, "maxMs": 0, "meanMs": 0, "p75Ms": 0, "p95Ms": 0, "p99Ms": 0}
          |        },
          |        "Person": {
          |          "pets": {"count": 1, "minMs": 0, "maxMs": 0, "meanMs": 0, "p75Ms": 0, "p95Ms": 0, "p99Ms": 0},
          |          "__typename": {"count": 1, "minMs": 0, "maxMs": 0, "meanMs": 0, "p75Ms": 0, "p95Ms": 0, "p99Ms": 0},
          |          "name": {"count": 1, "minMs": 0, "maxMs": 0, "meanMs": 0, "p75Ms": 0, "p95Ms": 0, "p99Ms": 0}
          |        }
          |      }
          |    }
          |  }
          |}""".stripMargin))
    }
  }

  def removeTime(json: JValue, unit: String, fieldUnit: String): JValue =
    json.transformField {
      case (en @ "extensions", ev) ⇒
        en → ev.transformField {
          case (n @ "query", JString(s)) ⇒
            n → JString(removeTime(s, unit))
          case (n, JInt(i)) if n endsWith fieldUnit ⇒
            n → JInt(0)
        }
    }

  def removeTime(query: String, unit: String): String =
    query.replaceAll("\\d+" + unit, "0" + unit)
}
