**sangria-slowlog** - [Sangria](http://sangria-graphql.org/) middleware to log slow queries.

[![Build Status](https://travis-ci.org/sangria-graphql-org/sangria-slowlog.svg?branch=master)](https://travis-ci.org/sangria-graphql-org/sangria-slowlog)
[![Coverage Status](http://coveralls.io/repos/sangria-graphql/sangria-slowlog/badge.svg?branch=master&service=github)](http://coveralls.io/github/sangria-graphql/sangria-slowlog?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.sangria-graphql/sangria-slowlog_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.sangria-graphql/sangria-slowlog_2.11)
[![License](http://img.shields.io/:license-Apache%202-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
[![Join the chat at https://gitter.im/sangria-graphql/sangria](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/sangria-graphql/sangria?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

SBT Configuration:

- last stable release:

```scala
libraryDependencies += "org.sangria-graphql" %% "sangria" % "1.4.2"
libraryDependencies += "org.sangria-graphql" %% "sangria-slowlog" % "0.1.8"
```

- 2.0.0 milestone, available for scala 2.13:

```scala
libraryDependencies += "org.sangria-graphql" %% "sangria" % "2.0.0-M1"
libraryDependencies += "org.sangria-graphql" %% "sangria-slowlog" % "2.0.0-M1"
```

## Usage

With middleware, Sangria provides a very convenient way to instrument GraphQL query execution and introduce profiling logic. `sangria-slowlog`
provides a simple mechanism to log slow queries and show profiling information.

Library provides a middleware that logs instrumented query information if execution exceeds specific threshold. An example:

```scala
import sangria.slowlog.SlowLog
import scala.concurrent.duration._

Executor.execute(schema, query,
  middleware = SlowLog(logger, threshold = 10 seconds) :: Nil)
```

If query execution takes more than 10 seconds to execute, then you will see similar info in the logs:

```graphql
# [Execution Metrics] duration: 12362ms, validation: 0ms, reducers: 0ms
#
# $id = "1000"
query Test($id: String!) {
  # [Query] count: 1, time: 2ms
  #
  # $id = "1000"
  human(id: $id) {
    # [Human] count: 1, time: 0ms
    name

    # [Human] count: 1, time: 11916ms
    appearsIn

    # [Human] count: 1, time: 358ms
    friends {
      # [Droid] count: 2, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
      # [Human] count: 2, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms
      name
    }
  }
}
```

`sangria-slowlog` has full support for GraphQL fragments and polymorphic types, so you will always see metrics for concrete types.

In addition to logging, `sangria-slowlog` also supports graphql extensions. Extensions will add a profiling info in the response under
`extensions` top-level field. In the most basic form, you can use it like this (this approach also disables the logging):

```scala

Executor.execute(schema, query, middleware = SlowLog.extension :: Nil)
```

After middleware is added, you will see following JSON in the response:

```json
{
  "data": {
    "human": {
      "name": "Luke Skywalker",
      "appearsIn": ["NEWHOPE", "EMPIRE", "JEDI"],
      "friends": [
        {"name": "Han Solo"},
        {"name": "Leia Organa"},
        {"name": "C-3PO"},
        {"name": "R2-D2"}
      ]
    }
  },
  "extensions": {
    "metrics": {
      "executionMs": 362,
      "validationMs": 0,
      "reducersMs": 0,
      "query": "# [Execution Metrics] duration: 362ms, validation: 0ms, reducers: 0ms\n#\n# $id = \"1000\"\nquery Test($id: String!) {\n  # [Query] count: 1, time: 2ms\n  #\n  # $id = \"1000\"\n  human(id: $id) {\n    # [Human] count: 1, time: 0ms\n    name\n\n    # [Human] count: 1, time: 216ms\n    appearsIn\n\n    # [Human] count: 1, time: 358ms\n    friends {\n      # [Droid] count: 2, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms\n      # [Human] count: 2, min: 0ms, max: 0ms, mean: 0ms, p75: 0ms, p95: 0ms, p99: 0ms\n      name\n    }\n  }\n}",
      "types": {
        "Human": {
          "friends": {
            "count": 1, "minMs": 358, "maxMs": 358, "meanMs": 358,
            "p75Ms": 358, "p95Ms": 358, "p99Ms": 358
          },
          "appearsIn": {
            "count": 1, "minMs": 216, "maxMs": 216, "meanMs": 216,
            "p75Ms": 216, "p95Ms": 216, "p99Ms": 216
          },
          "name": {
            "count": 3, "minMs": 0, "maxMs": 0, "meanMs": 0,
            "p75Ms": 0, "p95Ms": 0, "p99Ms": 0
          }
        },
        "Query": {
          "human": {
            "count": 1, "minMs": 2, "maxMs": 2, "meanMs": 2,
            "p75Ms": 2, "p95Ms": 2, "p99Ms": 2
          }
        },
        "Droid": {
          "name": {
            "count": 2, "minMs": 0, "maxMs": 0, "meanMs": 0,
            "p75Ms": 0, "p95Ms": 0, "p99Ms": 0
          }
        }
      }
    }
  }
}
```

All `SlowLog` methods accept `addExtensions` argument which allows you to include these extensions along the way.

With a small tweaking, you can also include "Profile" button in GraphiQL. On the server you just need to conditionally include
`SlowLog.extension` middleware to make it work. [Here is an example](https://youtu.be/OMa3SXC2mjA) of how this integration might look like.

## Apollo Tracing Extension

sangria-slowlog also supports [apollo-tracing](https://github.com/apollographql/apollo-tracing) format for metrics. In order
to enable the extension, just use `ApolloTracingExtension` middleware (or a shortcut `SlowLog.apolloTracing`).

Here is an example:

```scala
import sangria.slowlog.SlowLog

Executor.execute(schema, query, middleware = SlowLog.apolloTracing :: Nil)
```

## OpenTracing Support

sangria-slowlog supports [OpenTracing](http://opentracing.io/) with `OpenTracing` middleware. In order
to enable it, just use `OpenTracing` middleware (or a shortcut `SlowLog.openTracing`).

Here is an example:

```scala
import sangria.slowlog.SlowLog

Executor.execute(schema, query, middleware = SlowLog.openTracing() :: Nil)
```

You would need and implicit instance of a `Tracer` available in the scope.

In order to access field's `span` in the resolve function, you can use middleware attachment `ScopeAttachment`:

```scala
Fied(..., resolve = ctx => {
  val parentSpan: Option[Span] = ctx.attachment[ScopeAttachment].map(_.span)
  
  // ...
})
``` 

The middleware creates spans using OpenTracings `startActive` API, so you can inject the active span into a carrier, for instance an HTTP request, if resolving a field requires a network request, and follow the trace in the responding service.

## License

**sangria-slowlog** is licensed under [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).
