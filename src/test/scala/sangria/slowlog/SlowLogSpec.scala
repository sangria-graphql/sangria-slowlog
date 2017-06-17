package sangria.slowlog

import java.util.concurrent.TimeUnit

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
import sangria.marshalling.playJson._
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
      resolve = _.value.pets),
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
      val vars = ScalaInput.scalaInput(Map("limit" → 10))
      var enrichedQuery: Option[String] = None

      Executor.execute(schema, mainQuery,
        root = bob,
        operationName = Some("Test"),
        variables = vars,
        middleware = SlowLog.log((_, query) ⇒ enrichedQuery = Some(query), 0 seconds) :: Nil).await

      removeTime(enrichedQuery.value, "ms") should equal (
        """# [Execution Metrics] duration: 0ms, validation: 0ms, reducers: 0ms
          |#
          |# $limit = 10
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
          |  # $limit = 10
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
      val vars = ScalaInput.scalaInput(Map("limit" → 10))
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
          |# $limit = 10
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
          |  # $limit = 10
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
  }

  def removeTime(query: String, unit: String) =
    query.replaceAll("\\d+" + unit, "0" + unit)
}
