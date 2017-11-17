package sangria.slowlog

import org.json4s.JsonAST._

import language.postfixOps
import org.scalatest.{Matchers, OptionValues, WordSpec}
import sangria.execution.Executor
import sangria.marshalling.ScalaInput
import sangria.slowlog.util.{FutureResultSupport, StringMatchers}
import sangria.marshalling.json4s.native._
import org.json4s.native.JsonMethods._
import sangria.macros._

import scala.concurrent.ExecutionContext.Implicits.global

class ApolloTracingExtensionSpec extends WordSpec with Matchers with FutureResultSupport with StringMatchers with OptionValues  {
  import TestSchema._
  
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

  "ApolloTracingExtension" should {
    "Add tracing extension" in {
      val vars = ScalaInput.scalaInput(Map("limit" → 4))

      val result =
        Executor.execute(schema, mainQuery,
          root = bob,
          operationName = Some("Test"),
          variables = vars,
          middleware = SlowLog.apolloTracing :: Nil).await

      removeTime(result) should be (parse(
        """
        {
          "data": {
             "__typename": "Person",
             "name": "Bob",
             "pets": [
                {
                   "name": "Garfield",
                   "meows": false
                },
                {
                   "name": "Garfield",
                   "meows": false
                },
                {
                   "name": "Garfield",
                   "meows": false
                },
                {
                   "name": "Garfield",
                   "meows": false
                }
             ]
          },
          "extensions": {
             "tracing": {
                "version": 1,
                "startTime": "DATE",
                "endTime": "DATE",
                "duration": 0,
                "execution": {
                   "resolvers": [
                      {
                         "path": [
                            "__typename"
                         ],
                         "fieldName": "__typename",
                         "parentType": "Person",
                         "returnType": "String!",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "name"
                         ],
                         "fieldName": "name",
                         "parentType": "Person",
                         "returnType": "String",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets"
                         ],
                         "fieldName": "pets",
                         "parentType": "Person",
                         "returnType": "[Pet]",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            0,
                            "meows"
                         ],
                         "fieldName": "meows",
                         "parentType": "Cat",
                         "returnType": "Boolean",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            0,
                            "name"
                         ],
                         "fieldName": "name",
                         "parentType": "Cat",
                         "returnType": "String",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            1,
                            "meows"
                         ],
                         "fieldName": "meows",
                         "parentType": "Cat",
                         "returnType": "Boolean",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            1,
                            "name"
                         ],
                         "fieldName": "name",
                         "parentType": "Cat",
                         "returnType": "String",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            2,
                            "meows"
                         ],
                         "fieldName": "meows",
                         "parentType": "Cat",
                         "returnType": "Boolean",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            2,
                            "name"
                         ],
                         "fieldName": "name",
                         "parentType": "Cat",
                         "returnType": "String",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            3,
                            "meows"
                         ],
                         "fieldName": "meows",
                         "parentType": "Cat",
                         "returnType": "Boolean",
                         "startOffset": 0,
                         "duration": 0
                      },
                      {
                         "path": [
                            "pets",
                            3,
                            "name"
                         ],
                         "fieldName": "name",
                         "parentType": "Cat",
                         "returnType": "String",
                         "startOffset": 0,
                         "duration": 0
                      }
                   ]
                }
             }
          }
        }
        """))
    }
  }

  def removeTime(res: JValue) =
    res.transformField {
      case (name @ "startOffset", _) ⇒ name → JInt(0)
      case (name @ "duration", _) ⇒ name → JInt(0)
      case (name @ "startTime", _) ⇒ name → JString("DATE")
      case (name @ "endTime", _) ⇒ name → JString("DATE")
      case (name @ "resolvers", JArray(elems)) ⇒ name → JArray(elems.sortBy(e ⇒ (e \ "path").toString))
    }
}
