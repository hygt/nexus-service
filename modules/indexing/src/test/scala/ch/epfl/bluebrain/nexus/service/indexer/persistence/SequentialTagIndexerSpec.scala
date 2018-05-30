package ch.epfl.bluebrain.nexus.service.indexer.persistence

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

import akka.Done
import akka.cluster.Cluster
import akka.stream.ActorMaterializer
import akka.testkit.{TestActorRef, TestKit, TestKitBase}
import ch.epfl.bluebrain.nexus.commons.types.{Err, RetriableErr}
import ch.epfl.bluebrain.nexus.service.indexer.persistence.Fixture.{RetryExecuted, _}
import ch.epfl.bluebrain.nexus.service.indexer.persistence.SequentialTagIndexerSpec._
import ch.epfl.bluebrain.nexus.service.indexer.stream.StreamCoordinator
import ch.epfl.bluebrain.nexus.service.indexer.stream.StreamCoordinator.Stop
import ch.epfl.bluebrain.nexus.sourcing.akka._
import io.circe.generic.auto._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover, Matchers, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._

//noinspection TypeAnnotation
@DoNotDiscover
class SequentialTagIndexerSpec
    extends TestKitBase
    with WordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with Eventually {

  implicit lazy val system = SystemBuilder.cluster("SequentialTagIndexerSpec")
  implicit val ec          = system.dispatcher
  implicit val mt          = ActorMaterializer()

  private val cluster = Cluster(system)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    cluster.join(cluster.selfAddress)
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(30 seconds, 1 second)

  "A SequentialTagIndexer" should {
    val pluginId         = "cassandra-query-journal"
    val sourcingSettings = SourcingAkkaSettings(journalPluginId = pluginId)

    def initFunction(init: AtomicLong): () => Future[Unit] =
      () => {
        init.incrementAndGet()
        Future.successful(())
      }

    "index existing events" in {
      val agg = ShardingAggregate("agg", sourcingSettings)(Fixture.initial, Fixture.next, Fixture.eval)
      agg.append(PersistenceId("first"), Fixture.Executed).futureValue

      val count = new AtomicLong(0L)
      val init  = new AtomicLong(10L)
      val index = (_: Event) =>
        Future.successful[Unit] {
          val _ = count.incrementAndGet()
      }
      val projId   = UUID.randomUUID().toString
      val keyspace = "keyspace"

      val initialize = SequentialTagIndexer.initialize(initFunction(init), projId, keyspace)
      val source     = SequentialTagIndexer.source(index, projId, keyspace, pluginId, "executed")
      val indexer    = TestActorRef(new StreamCoordinator(initialize, source))

      eventually {
        count.get() shouldEqual 1L
        init.get shouldEqual 11L
      }

      watch(indexer)
      indexer ! Stop
      expectTerminated(indexer)
    }

    "select only the configured event types" in {
      val agg = ShardingAggregate("selected", sourcingSettings)(Fixture.initial, Fixture.next, Fixture.eval)
      agg.append(PersistenceId("first"), Fixture.Executed).futureValue
      agg.append(PersistenceId("second"), Fixture.Executed).futureValue
      agg.append(PersistenceId("third"), Fixture.Executed).futureValue
      agg.append(PersistenceId("selected"), Fixture.OtherExecuted).futureValue
      agg.append(PersistenceId("selected"), Fixture.OtherExecuted).futureValue

      val count = new AtomicLong(0L)
      val init  = new AtomicLong(10L)

      val index = (_: OtherExecuted.type) =>
        Future.successful[Unit] {
          val _ = count.incrementAndGet()
      }
      val projId = UUID.randomUUID().toString
      val keyspace = "keyspace"

      val initialize = SequentialTagIndexer.initialize(initFunction(init), projId, keyspace)
      val source     = SequentialTagIndexer.source(index, projId, keyspace, pluginId, "other")
      val indexer    = TestActorRef(new StreamCoordinator(initialize, source))

      eventually {
        count.get() shouldEqual 2L
        init.get shouldEqual 11L
      }

      watch(indexer)
      indexer ! Stop
      expectTerminated(indexer)
    }

    "select event types for a given keyspace" in {
      val agg = ShardingAggregate("keyspaces", sourcingSettings)(Fixture.initial, Fixture.next, Fixture.eval)
      val keyspace = "ks1"
      val other = "ks2"
      agg.append(PersistenceId("first", keyspace), Fixture.Executed).futureValue
      agg.append(PersistenceId("second", keyspace), Fixture.Executed).futureValue
      agg.append(PersistenceId("third", keyspace), Fixture.Executed).futureValue
      agg.append(PersistenceId("first", other), Fixture.Executed).futureValue
      agg.append(PersistenceId("second", other), Fixture.Executed).futureValue
      agg.append(PersistenceId("third", other), Fixture.Executed).futureValue

      val count = new AtomicLong(0L)
      val init  = new AtomicLong(10L)

      val index = (e: Event) =>
        Future.successful[Unit] {
          val _ = count.incrementAndGet()
      }
      val projId   = UUID.randomUUID().toString

      val initialize = SequentialTagIndexer.initialize(initFunction(init), projId, keyspace)
      val source     = SequentialTagIndexer.source(index, projId, keyspace, pluginId, "keyspaces")
      val indexer    = TestActorRef(new StreamCoordinator(initialize, source))

      eventually {
        count.get shouldEqual 3L
        init.get shouldEqual 11L
      }

      watch(indexer)
      indexer ! Stop
      expectTerminated(indexer)
    }

    "restart the indexing if the Done is emitted" in {
      val agg = ShardingAggregate("agg", sourcingSettings)(Fixture.initial, Fixture.next, Fixture.eval)
      agg.append(PersistenceId("first"), Fixture.AnotherExecuted).futureValue

      val count = new AtomicLong(0L)
      val init  = new AtomicLong(10L)
      val index = (_: Event) =>
        Future.successful[Unit] {
          val _ = count.incrementAndGet()
      }
      val projId   = UUID.randomUUID().toString
      val keyspace = "keyspace"

      val initialize = SequentialTagIndexer.initialize(initFunction(init), projId, keyspace)
      val source     = SequentialTagIndexer.source(index, projId, keyspace, pluginId, "another")
      val indexer    = TestActorRef(new StreamCoordinator(initialize, source))

      eventually {
        count.get() shouldEqual 1L
        init.get shouldEqual 11L
      }
      indexer ! Done

      agg.append(PersistenceId("second"), Fixture.AnotherExecuted).futureValue

      eventually {
        count.get() shouldEqual 2L
        init.get shouldEqual 12L
      }

      watch(indexer)
      indexer ! Stop
      expectTerminated(indexer)
    }

    "retry when index function fails" in {
      val agg = ShardingAggregate("retry", sourcingSettings)(Fixture.initial, Fixture.next, Fixture.eval)
      agg.append(PersistenceId("retry"), Fixture.RetryExecuted).futureValue

      val count = new AtomicLong(0L)
      val init  = new AtomicLong(10L)

      val index    = (_: RetryExecuted.type) => Future.failed[Unit](SomeError(count.incrementAndGet()))
      val projId   = UUID.randomUUID().toString
      val keyspace = "keyspace"

      val initialize = SequentialTagIndexer.initialize(initFunction(init), projId, keyspace)
      val source     = SequentialTagIndexer.source(index, projId, keyspace, pluginId, "retry")
      val indexer    = TestActorRef(new StreamCoordinator(initialize, source))

      eventually {
        count.get() shouldEqual 4
        init.get shouldEqual 11L
      }
      eventually {
        IndexFailuresLog(projId)
          .fetchEvents[RetryExecuted.type]
          .runFold(Vector.empty[RetryExecuted.type])(_ :+ _)
          .futureValue shouldEqual List(RetryExecuted)
      }

      watch(indexer)
      indexer ! Stop
      expectTerminated(indexer)
    }

    "not retry when index function fails with a non RetriableErr" in {
      val agg = ShardingAggregate("ignore", sourcingSettings)(Fixture.initial, Fixture.next, Fixture.eval)
      agg.append(PersistenceId("ignore"), Fixture.IgnoreExecuted).futureValue

      val count = new AtomicLong(0L)
      val init  = new AtomicLong(10L)

      val index =
        (_: IgnoreExecuted.type) => Future.failed[Unit](SomeOtherError(count.incrementAndGet()))
      val projId   = UUID.randomUUID().toString
      val keyspace = "keyspace"

      val initialize = SequentialTagIndexer.initialize(initFunction(init), projId, keyspace)
      val source     = SequentialTagIndexer.source(index, projId, keyspace, pluginId, "ignore")
      val indexer    = TestActorRef(new StreamCoordinator(initialize, source))

      eventually {
        count.get() shouldEqual 1L
        init.get shouldEqual 11L
      }

      IndexFailuresLog(projId)
        .fetchEvents[IgnoreExecuted.type]
        .runFold(Vector.empty[IgnoreExecuted.type])(_ :+ _)
        .futureValue shouldEqual List(IgnoreExecuted)

      watch(indexer)
      indexer ! Stop
      expectTerminated(indexer)
    }
  }

}

object SequentialTagIndexerSpec {
  case class SomeError(count: Long)      extends RetriableErr("some error")
  case class SomeOtherError(count: Long) extends Err("some OTHER error")

}
