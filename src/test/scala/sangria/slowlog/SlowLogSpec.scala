package sangria.slowlog

import java.util.concurrent.TimeUnit

import com.codahale.metrics.{Counter, Histogram, MetricRegistry}

import sangria.slowlog.util.{DebugUtil, FutureResultSupport}
import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.Json
import sangria.execution._
import sangria.schema._
import sangria.macros._
import sangria.ast
import sangria.ast.AstVisitor
import sangria.marshalling.ScalaInput
import sangria.visitor.VisitorCommand

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import sangria.marshalling.playJson._
import sangria.slowlog.util._

class SlowLogSpec extends WordSpec with Matchers with FutureResultSupport {

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

  "SlowLog" should {
    "do stuff" in {
      // just experimenting at the moment
      val query =
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

      val vars = ScalaInput.scalaInput(Map("limit" → 10))

//      Executor.execute(schema, query, root = bob, variables = vars, middleware = SlowLog.extension :: Nil)

      val res = Executor.execute(schema, query, root = bob, operationName = Some("Test"), variables = vars, middleware = SlowLog.print(separateOp = false) :: Nil).await

//      println(Json.prettyPrint(res))

//      import sangria.execution.ExecutionScheme.Extended

//      val metrics =
//        SlowLog.extractQueryMetrics(
//          Executor.execute(schema, query, root = bob, variables = vars, middleware = SlowLog.extension :: Nil).await)
//
//      println(metrics)
    }
  }
}
