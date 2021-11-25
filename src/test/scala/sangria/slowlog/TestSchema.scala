package sangria.slowlog

import sangria.schema._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object TestSchema {
  trait Named {
    def name: Option[String]
  }

  case class Dog(name: Option[String], barks: Option[Boolean]) extends Named
  case class Cat(name: Option[String], meows: Option[Boolean]) extends Named
  case class Person(
      name: Option[String],
      pets: Option[List[Option[Any]]],
      friends: Option[List[Option[Named]]])
      extends Named

  val NamedType: InterfaceType[Unit, Named] = InterfaceType(
    "Named",
    fields[Unit, Named](Field("name", OptionType(StringType), resolve = _.value.name)))

  val DogType: ObjectType[Unit, Dog] = ObjectType(
    "Dog",
    interfaces[Unit, Dog](NamedType),
    fields[Unit, Dog](
      Field("name", OptionType(StringType), resolve = _.value.name),
      Field("barks", OptionType(BooleanType), resolve = _.value.barks))
  )

  val CatType: ObjectType[Unit, Cat] = ObjectType(
    "Cat",
    interfaces[Unit, Cat](NamedType),
    fields[Unit, Cat](
      Field(
        "name",
        OptionType(StringType),
        resolve = c =>
          Future {
            Thread.sleep((math.random() * 10).toLong)
            c.value.name
          }
      ),
      Field("meows", OptionType(BooleanType), resolve = _.value.meows)
    )
  )

  val PetType: UnionType[Unit] = UnionType[Unit]("Pet", types = DogType :: CatType :: Nil)

  val LimitArg: Argument[Int] = Argument("limit", OptionInputType(IntType), 10)

  val PersonType: ObjectType[Unit, Person] = ObjectType(
    "Person",
    interfaces[Unit, Person](NamedType),
    fields[Unit, Person](
      Field(
        "pets",
        OptionType(ListType(OptionType(PetType))),
        arguments = LimitArg :: Nil,
        resolve = c => c.withArgs(LimitArg)(limit => c.value.pets.map(_.take(limit)))),
      Field("favouritePet", PetType, resolve = _.value.pets.flatMap(_.headOption.flatten).get),
      Field(
        "favouritePetList",
        ListType(PetType),
        resolve = _.value.pets.getOrElse(Nil).flatten.toSeq),
      Field(
        "favouritePetOpt",
        OptionType(PetType),
        resolve = _.value.pets.flatMap(_.headOption.flatten)),
      Field("friends", OptionType(ListType(OptionType(NamedType))), resolve = _.value.friends)
    )
  )

  val TestSchema: Schema[Unit, Person] = Schema(PersonType)

  val garfield: Cat = Cat(Some("Garfield"), Some(false))
  val odie: Dog = Dog(Some("Odie"), Some(true))
  val liz: Person = Person(Some("Liz"), None, None)
  val bob: Person = Person(
    Some("Bob"),
    Some(Iterator.continually(Some(garfield)).take(20).toList :+ Some(odie)),
    Some(List(Some(liz), Some(odie))))

  val schema: Schema[Unit, Person] = Schema(PersonType)
}
