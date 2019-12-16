package sample.cqrs

import akka.NotUsed

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{NoOffset, PersistenceQuery}
import akka.stream.scaladsl
import akka.util.Timeout
import spray.json.DefaultJsonProtocol


object ShoppingCartRoutes {
  final case class AddItem(cartId: String, itemId: String, quantity: Int)
  final case class UpdateItem(cartId: String, itemId: String, quantity: Int)
}

class ShoppingCartRoutes()(implicit system: ActorSystem[_]) {

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("shopping.askTimeout"))
  private val sharding = ClusterSharding(system)

  import ShoppingCartRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._
  import JsonFormats._

  val shopping: Route =
    pathPrefix("shopping") {
      pathPrefix("carts") {
        concat(
          post {
            entity(as[AddItem]) { data =>
              val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, data.cartId)
              val reply: Future[ShoppingCart.Confirmation] =
                entityRef.ask(ShoppingCart.AddItem(data.itemId, data.quantity, _))
              onSuccess(reply) {
                case ShoppingCart.Accepted(summary) =>
                  complete(StatusCodes.OK -> summary)
                case ShoppingCart.Rejected(reason) =>
                  complete(StatusCodes.BadRequest, reason)
              }
            }
          },
          put {
            entity(as[UpdateItem]) {
              data =>
                val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, data.cartId)

                def command(replyTo: ActorRef[ShoppingCart.Confirmation]) =
                  if (data.quantity == 0) ShoppingCart.RemoveItem(data.itemId, replyTo)
                  else ShoppingCart.AdjustItemQuantity(data.itemId, data.quantity, replyTo)

                val reply: Future[ShoppingCart.Confirmation] = entityRef.ask(command(_))
                onSuccess(reply) {
                  case ShoppingCart.Accepted(summary) =>
                    complete(StatusCodes.OK -> summary)
                  case ShoppingCart.Rejected(reason) =>
                    complete(StatusCodes.BadRequest, reason)
                }
            }
          },
          path("all") {
            get {
              import akka.actor.typed.scaladsl.adapter._
              val queries = PersistenceQuery(system.toClassic)
                .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

              val shoppingCarts: scaladsl.Source[ShoppingCart.ItemAdded, NotUsed] = queries
                .eventsByTag(ShoppingCart.CART_OPENED, NoOffset)
                .map {
                  eventEnvelope =>
                    eventEnvelope.event match {
                      case shoppingCartItemAdded: ShoppingCart.ItemAdded =>
                        system.log.info(s"Shopping Cart ID: ${shoppingCartItemAdded.cartId} Item ID: ${shoppingCartItemAdded.itemId} Quantity: ${shoppingCartItemAdded.quantity}")
                        Some(shoppingCartItemAdded)
                      case _ => None
                    }
                }.collect {
                case Some(itemAdded) => itemAdded
              }

              implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
                EntityStreamingSupport.json()

              complete(shoppingCarts)
            }
          },
          path("nothing") {
            get {
              getShoppingCartsForTag(ShoppingCart.CART_OPENED)
              getShoppingCartsForTag(ShoppingCart.CART_ADJUSTED)
              getShoppingCartsForTag(ShoppingCart.CART_REMOVED)
              getShoppingCartsForTag(ShoppingCart.CART_CHECKED_OUT)
              complete(StatusCodes.OK -> "Logged Shopping Carts")
            }
          },
          path("future") {
            get {

              import akka.actor.typed.scaladsl.adapter._
              val queries = PersistenceQuery(system.toClassic)
                .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

              queries
                .eventsByTag(ShoppingCart.CART_OPENED, NoOffset)
                .runForeach {
                  eventEnvelope =>
                    eventEnvelope.event match {
                      case shoppingCartItemAdded: ShoppingCart.ItemAdded =>
                        system.log.info(s"Shopping Cart ID: ${shoppingCartItemAdded.cartId} Item ID: ${shoppingCartItemAdded.itemId} Quantity: ${shoppingCartItemAdded.quantity}")
                      case event =>
                        system.log.info(s"Event Type: $event")
                    }
                }
              complete(StatusCodes.OK -> "Logged Shopping Carts")
            }
          },
          pathPrefix(Segment) { cartId =>
            concat(get {
              val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, cartId)
              onSuccess(entityRef.ask(ShoppingCart.Get)) { summary =>
                if (summary.items.isEmpty) complete(StatusCodes.NotFound)
                else complete(summary)
              }
            }, path("checkout") {
              post {
                val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, cartId)
                val reply: Future[ShoppingCart.Confirmation] = entityRef.ask(ShoppingCart.Checkout(_))
                onSuccess(reply) {
                  case ShoppingCart.Accepted(summary) =>
                    complete(StatusCodes.OK -> summary)
                  case ShoppingCart.Rejected(reason) =>
                    complete(StatusCodes.BadRequest, reason)
                }
              }
            })
          })
      }
    }


  private def getShoppingCartsForTag(tag:String): Unit = {
    import akka.actor.typed.scaladsl.adapter._
    val queries = PersistenceQuery(system.toClassic)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    system.log.info(s"Shopping Carts Tagged: $tag")

    queries
      .eventsByTag(tag, NoOffset)
      .runForeach {
        eventEnvelope =>
          eventEnvelope.event match {
            case shoppingCartItemAdded: ShoppingCart.ItemAdded =>
              system.log.info(s"Shopping Cart ID: ${shoppingCartItemAdded.cartId} Item ID: ${shoppingCartItemAdded.itemId} Quantity: ${shoppingCartItemAdded.quantity}")
            case event =>
              system.log.info(s"Event Type: $event")
          }
      }

    system.log.info(s"\n\n\n\n")

  }

}

object JsonFormats extends DefaultJsonProtocol {

  import spray.json.RootJsonFormat
  // import the default encoders for primitive types (Int, String, Lists etc)
  import spray.json.DefaultJsonProtocol._

  implicit val summaryFormat: RootJsonFormat[ShoppingCart.Summary] = jsonFormat2(ShoppingCart.Summary)
  implicit val addItemFormat: RootJsonFormat[ShoppingCartRoutes.AddItem] = jsonFormat3(ShoppingCartRoutes.AddItem)
  implicit val updateItemFormat: RootJsonFormat[ShoppingCartRoutes.UpdateItem] = jsonFormat3(
    ShoppingCartRoutes.UpdateItem)

  implicit val itemAddedFormat: RootJsonFormat[ShoppingCart.ItemAdded] = jsonFormat3(ShoppingCart.ItemAdded)

}
