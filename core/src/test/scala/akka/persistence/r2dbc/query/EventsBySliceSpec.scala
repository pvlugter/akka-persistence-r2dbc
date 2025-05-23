/*
 * Copyright (C) 2022 - 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.query

import scala.concurrent.Await

import akka.Done
import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.persistence.FilteredPayload
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.TimestampOffset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.scaladsl.EventTimestampQuery
import akka.persistence.query.typed.scaladsl.LatestEventTimestampQuery
import akka.persistence.query.typed.scaladsl.LoadEventQuery
import akka.persistence.r2dbc.RetryableTests
import akka.persistence.r2dbc.TestActors
import akka.persistence.r2dbc.TestActors.Persister
import akka.persistence.r2dbc.TestActors.Persister.PersistAll
import akka.persistence.r2dbc.TestActors.Persister.PersistWithAck
import akka.persistence.r2dbc.TestActors.Persister.Ping
import akka.persistence.r2dbc.TestConfig
import akka.persistence.r2dbc.TestData
import akka.persistence.r2dbc.TestDbLifecycle
import akka.persistence.r2dbc.internal.InstantFactory
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.internal.ReplicatedEventMetadata
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.tagobjects.Retryable
import org.scalatest.wordspec.AnyWordSpecLike

object EventsBySliceSpec {
  sealed trait QueryType
  case object Live extends QueryType
  case object Current extends QueryType

  def config: Config =
    TestConfig.backtrackingDisabledConfig
      .withFallback(ConfigFactory.parseString(s"""
    # This test is not using backtracking, so increase behind-current-time to
    # reduce risk of missing events
    akka.persistence.r2dbc.query.behind-current-time = 500 millis
    akka.persistence.r2dbc-small-buffer = $${akka.persistence.r2dbc}

    akka.persistence.r2dbc.journal.publish-events = off

    # this is used by the "read in chunks" test
    akka.persistence.r2dbc-small-buffer.query {
      buffer-size = 4
      # for this extreme scenario it will add delay between each query for the live case
      refresh-interval = 20 millis
    }

    # for "cache LatestEventTimestampQuery results when configured" test
    akka.persistence.r2dbc-cache-latest-event-timestamp = $${akka.persistence.r2dbc}
    akka.persistence.r2dbc-cache-latest-event-timestamp.query {
      cache-latest-event-timestamp = 2s
    }
    """))
      .withFallback(TestConfig.config)
      .resolve()
}

class EventsBySliceSpec
    extends ScalaTestWithActorTestKit(EventsBySliceSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with RetryableTests
    with LogCapturing {
  import EventsBySliceSpec._

  override def typedSystem: ActorSystem[_] = system

  private val query = PersistenceQuery(testKit.system).readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)

  private class Setup {
    val entityType = nextEntityType()
    val persistenceId = nextPid(entityType)
    val slice = query.sliceForPersistenceId(persistenceId)
    val persister = spawn(TestActors.Persister(persistenceId))
    val probe = createTestProbe[Done]()
    val sinkProbe = TestSink.probe[EventEnvelope[String]](system.classicSystem)
  }

  List[QueryType](Current, Live).foreach { queryType =>
    def doQuery(
        entityType: String,
        minSlice: Int,
        maxSlice: Int,
        offset: Offset,
        queryImpl: R2dbcReadJournal = query): Source[EventEnvelope[String], NotUsed] =
      queryType match {
        case Live =>
          queryImpl.eventsBySlices[String](entityType, minSlice, maxSlice, offset)
        case Current =>
          queryImpl.currentEventsBySlices[String](entityType, minSlice, maxSlice, offset)
      }

    def assertFinished(probe: TestSubscriber.Probe[EventEnvelope[String]]): Unit =
      queryType match {
        case Live =>
          probe.expectNoMessage()
          probe.cancel()
        case Current =>
          probe.expectComplete()
      }

    s"$queryType eventsBySlices" should {
      "return all events for NoOffset" in new Setup {
        for (i <- 1 to 20) {
          persister ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }
        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slice, slice, NoOffset)
            .runWith(sinkProbe)
            .request(21)
        for (i <- 1 to 20) {
          result.expectNext().event shouldBe s"e-$i"
        }
        assertFinished(result)
      }

      "only return events after an offset" in new Setup {
        for (i <- 1 to 20) {
          persister ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }

        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slice, slice, NoOffset)
            .runWith(sinkProbe)
            .request(21)

        result.expectNextN(9)

        val offset = result.expectNext().offset
        result.cancel()

        val withOffset =
          doQuery(entityType, slice, slice, offset)
            .runWith(TestSink.probe[EventEnvelope[String]](system.classicSystem))
        withOffset.request(12)
        for (i <- 11 to 20) {
          withOffset.expectNext().event shouldBe s"e-$i"
        }
        assertFinished(withOffset)
      }

      "read in chunks" in new Setup {
        val queryWithSmallBuffer = PersistenceQuery(testKit.system)
          .readJournalFor[R2dbcReadJournal]("akka.persistence.r2dbc-small-buffer.query")
        for (i <- 1 to 10; n <- 1 to 10 by 2) {
          persister ! PersistAll(List(s"e-$i-$n", s"e-$i-${n + 1}"))
        }
        persister ! Ping(probe.ref)
        probe.expectMessage(Done)
        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slice, slice, NoOffset, queryWithSmallBuffer)
            .runWith(sinkProbe)
            .request(101)
        for (i <- 1 to 10; n <- 1 to 10) {
          result.expectNext().event shouldBe s"e-$i-$n"
        }
        assertFinished(result)
      }

      "handle more events with same timestamp than buffer size" in new Setup {
        val queryWithSmallBuffer = PersistenceQuery(testKit.system) // buffer size = 4
          .readJournalFor[R2dbcReadJournal]("akka.persistence.r2dbc-small-buffer.query")
        persister ! PersistAll((1 to 10).map(i => s"e-$i").toList)
        persister ! Ping(probe.ref)
        probe.expectMessage(Done)
        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slice, slice, NoOffset, queryWithSmallBuffer)
            .runWith(sinkProbe)
            .request(11)
        for (i <- 1 to 10) {
          result.expectNext().event shouldBe s"e-$i"
        }
        assertFinished(result)
      }

      "handle more events with same timestamp than buffer size, with overlapping seq numbers" in {
        val entityType = nextEntityType()
        val pid1 = nextPid(entityType)
        val pid2 = nextPid(entityType)
        val pid3 = nextPid(entityType)
        val pid4 = nextPid(entityType)
        val slice1 = query.sliceForPersistenceId(pid1)
        val slice2 = query.sliceForPersistenceId(pid2)
        val slice3 = query.sliceForPersistenceId(pid3)
        val slice4 = query.sliceForPersistenceId(pid4)
        val slices = Seq(slice1, slice2, slice3, slice4)
        val t1 = InstantFactory.now().minusSeconds(10)
        val t2 = t1.plusMillis(1)

        writeEvent(slice1, pid1, 1L, t1, "A1")
        writeEvent(slice1, pid1, 2L, t1, "A2")
        writeEvent(slice1, pid1, 3L, t1, "A3")
        writeEvent(slice1, pid1, 4L, t1, "A4")
        writeEvent(slice1, pid1, 5L, t1, "A5")
        writeEvent(slice1, pid1, 6L, t1, "A6")
        writeEvent(slice2, pid2, 3L, t1, "B3")
        writeEvent(slice2, pid2, 4L, t1, "B4")
        writeEvent(slice3, pid3, 3L, t1, "C3")
        writeEvent(slice4, pid4, 1L, t2, "D1")
        writeEvent(slice4, pid4, 2L, t2, "D2")
        writeEvent(slice4, pid4, 3L, t2, "D3")

        val queryWithSmallBuffer = PersistenceQuery(testKit.system) // buffer size = 4
          .readJournalFor[R2dbcReadJournal]("akka.persistence.r2dbc-small-buffer.query")

        val sinkProbe = TestSink[EventEnvelope[String]]()

        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slices.min, slices.max, NoOffset, queryWithSmallBuffer)
            .runWith(sinkProbe)
            .request(15)

        def take(n: Int): Set[String] =
          (1 to n).map(_ => result.expectNext().event).toSet

        take(1) shouldBe Set("A1")
        take(1) shouldBe Set("A2")
        take(3) shouldBe Set("A3", "B3", "C3")
        take(2) shouldBe Set("A4", "B4")
        take(1) shouldBe Set("A5")
        take(1) shouldBe Set("A6")
        take(1) shouldBe Set("D1")
        take(1) shouldBe Set("D2")
        take(1) shouldBe Set("D3")

        assertFinished(result)
      }

      "include metadata" in {
        val probe = testKit.createTestProbe[Done]()
        val entityType = nextEntityType()
        val entityId = "entity-1"
        val persistenceId = TestActors.replicatedEventSourcedPersistenceId(entityType, entityId)
        val slice = query.sliceForPersistenceId(persistenceId.id)

        val persister = testKit.spawn(TestActors.replicatedEventSourcedPersister(entityType, entityId))
        persister ! Persister.PersistWithAck("e-1", probe.ref)
        probe.expectMessage(Done)
        persister ! Persister.PersistWithAck("e-2", probe.ref)
        probe.expectMessage(Done)

        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slice, slice, NoOffset)
            .runWith(TestSink())
            .request(21)

        val env1 = result.expectNext()
        env1.event shouldBe "e-1"
        val meta1 = env1.eventMetadata.get.asInstanceOf[ReplicatedEventMetadata]
        meta1.originReplica.id shouldBe "dc-1"
        meta1.originSequenceNr shouldBe 1L

        val env2 = result.expectNext()
        env2.event shouldBe "e-2"
        val meta2 = env2.eventMetadata.get.asInstanceOf[ReplicatedEventMetadata]
        meta2.originReplica.id shouldBe "dc-1"
        meta2.originSequenceNr shouldBe 2L

        assertFinished(result)
      }

      "support EventTimestampQuery" in new Setup {
        for (i <- 1 to 3) {
          persister ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }

        query.isInstanceOf[EventTimestampQuery] shouldBe true
        query.timestampOf(persistenceId, 2L).futureValue.isDefined shouldBe true
        query.timestampOf(persistenceId, 1L).futureValue.isDefined shouldBe true
        query.timestampOf(persistenceId, 4L).futureValue.isDefined shouldBe false
      }

      "support LatestEventTimestampQuery" in new Setup {
        for (i <- 1 to 3) {
          persister ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }

        query shouldBe a[LatestEventTimestampQuery]

        val partitions = settings.numberOfDataPartitions
        val testNumRanges =
          if (partitions > 1) List(partitions, partitions * 2, 1024)
          else List(1, 4, 1024)
        testNumRanges.foreach { numRanges =>
          withClue(s"numRanges=$numRanges: ") {
            // test all slice ranges, with the events expected in one of the ranges
            val rangeSize = 1024 / numRanges
            val expectedRangeIndex = slice / rangeSize

            def sliceRange(rangeIndex: Int): (Int, Int) = {
              val minSlice = rangeIndex * rangeSize
              val maxSlice = minSlice + rangeSize - 1
              minSlice -> maxSlice
            }

            for (rangeIndex <- 0 until numRanges) {
              val (minSlice, maxSlice) = sliceRange(rangeIndex)
              val expectedTimestamp =
                if (rangeIndex != expectedRangeIndex) None
                else query.timestampOf(persistenceId, 3L).futureValue
              query.latestEventTimestamp(entityType, minSlice, maxSlice).futureValue shouldBe expectedTimestamp
            }
          }
        }
      }

      "cache LatestEventTimestampQuery results when configured" taggedAs Retryable in {
        val entityType = nextEntityType()
        val pid = nextPid(entityType)
        val slice = query.sliceForPersistenceId(pid)
        val persister = spawn(TestActors.Persister(pid))
        val probe = createTestProbe[Done]()

        persister ! PersistWithAck("e1", probe.ref)
        probe.expectMessage(Done)

        val queryWithCache =
          PersistenceQuery(system).readJournalFor[R2dbcReadJournal](
            "akka.persistence.r2dbc-cache-latest-event-timestamp.query")

        // first query will fetch from the database
        val expectedTimestamp1 = queryWithCache.timestampOf(pid, 1L).futureValue
        val timestamp1 = queryWithCache.latestEventTimestamp(entityType, slice, slice).futureValue
        timestamp1 shouldBe expectedTimestamp1

        persister ! PersistWithAck("e2", probe.ref)
        probe.expectMessage(Done)

        // second query will return cached result (when still within TTL of 2 seconds)
        val timestamp2 = queryWithCache.latestEventTimestamp(entityType, slice, slice).futureValue
        timestamp2 shouldBe timestamp1

        // after clearing cache, will fetch the latest timestamp
        queryWithCache.clearLatestEventTimestampCache()
        val expectedTimestamp3 = queryWithCache.timestampOf(pid, 2L).futureValue
        val timestamp3 = queryWithCache.latestEventTimestamp(entityType, slice, slice).futureValue
        timestamp3 shouldBe expectedTimestamp3

        persister ! PersistWithAck("e3", probe.ref)
        probe.expectMessage(Done)

        // make sure cached value has expired
        Thread.sleep(2000)

        // new result fetched from database after cache expiry
        val expectedTimestamp4 = queryWithCache.timestampOf(pid, 3L).futureValue
        val timestamp4 = queryWithCache.latestEventTimestamp(entityType, slice, slice).futureValue
        timestamp4 shouldBe expectedTimestamp4

        persister ! PersistWithAck("e4", probe.ref)
        probe.expectMessage(Done)

        // next query will return cached result again (when still within TTL of 2 seconds)
        val timestamp5 = queryWithCache.latestEventTimestamp(entityType, slice, slice).futureValue
        timestamp5 shouldBe timestamp4
      }

      "support LoadEventQuery" in new Setup {
        for (i <- 1 to 3) {
          persister ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }

        query.isInstanceOf[LoadEventQuery] shouldBe true
        query.loadEnvelope[String](persistenceId, 2L).futureValue.event shouldBe "e-2"
        query.loadEnvelope[String](persistenceId, 1L).futureValue.event shouldBe "e-1"
        intercept[NoSuchElementException] {
          Await.result(query.loadEnvelope[String](persistenceId, 4L), patience.timeout)
        }
      }

      "includes tags" in new Setup {
        val taggingPersister: ActorRef[Persister.Command] =
          spawn(TestActors.Persister(PersistenceId.ofUniqueId(persistenceId), tags = Set("tag-A")))
        for (i <- 1 to 3) {
          taggingPersister ! PersistWithAck(s"f-$i", probe.ref)
          probe.expectMessage(Done)
        }

        val result: TestSubscriber.Probe[EventEnvelope[String]] =
          doQuery(entityType, slice, slice, NoOffset)
            .runWith(TestSink())

        result.request(3)
        val envelopes = result.expectNextN(3)
        envelopes.map(_.tags) should ===(Seq(Set("tag-A"), Set("tag-A"), Set("tag-A")))

        query.loadEnvelope[String](persistenceId, 1L).futureValue.tags shouldBe Set("tag-A")

        assertFinished(result)
      }

      "mark FilteredEventPayload as filtered with no payload when reading it" in new Setup {
        persister ! PersistWithAck(FilteredPayload, probe.ref)
        probe.receiveMessage()

        {
          val result: TestSubscriber.Probe[EventEnvelope[String]] =
            doQuery(entityType, slice, slice, NoOffset)
              .runWith(TestSink())

          result.request(1)
          val envelope = result.expectNext()
          envelope.filtered should be(true)
          envelope.eventOption should be(empty)
          assertFinished(result)
        }

        {
          val envelope = query.loadEnvelope[String](persistenceId, 1L).futureValue
          envelope.filtered should ===(true)
          envelope.eventOption should be(empty)
        }
      }

    }
  }

  // tests just relevant for current query
  "Current eventsBySlices" should {
    "filter events with the same timestamp based on seen sequence nrs" in new Setup {
      persister ! PersistWithAck(s"e-1", probe.ref)
      probe.expectMessage(Done)
      val singleEvent: EventEnvelope[String] =
        query.currentEventsBySlices[String](entityType, slice, slice, NoOffset).runWith(Sink.head).futureValue
      val offset = singleEvent.offset.asInstanceOf[TimestampOffset]
      offset.seen shouldEqual Map(singleEvent.persistenceId -> singleEvent.sequenceNr)
      query
        .currentEventsBySlices[String](entityType, slice, slice, offset)
        .take(1)
        .runWith(Sink.headOption)
        .futureValue shouldEqual None
    }

    "not filter events with the same timestamp based on sequence nrs" in new Setup {
      persister ! PersistWithAck(s"e-1", probe.ref)
      probe.expectMessage(Done)
      val singleEvent: EventEnvelope[String] =
        query.currentEventsBySlices[String](entityType, slice, slice, NoOffset).runWith(Sink.head).futureValue
      val offset = singleEvent.offset.asInstanceOf[TimestampOffset]
      offset.seen shouldEqual Map(singleEvent.persistenceId -> singleEvent.sequenceNr)

      val offsetWithoutSeen = TimestampOffset(offset.timestamp, Map.empty)
      val singleEvent2 = query
        .currentEventsBySlices[String](entityType, slice, slice, offsetWithoutSeen)
        .runWith(Sink.headOption)
        .futureValue
      singleEvent2.get.event shouldBe "e-1"
    }

    "retrieve from several slices" in new Setup {
      val numberOfPersisters = 20
      val numberOfEvents = 3
      val persistenceIds = (1 to numberOfPersisters).map(_ => nextPid(entityType)).toVector
      val persisters = persistenceIds.map { pid =>
        val ref = testKit.spawn(TestActors.Persister(pid))
        for (i <- 1 to numberOfEvents) {
          ref ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }
      }

      persistenceExt.numberOfSlices should be(1024)
      val ranges = query.sliceRanges(4)
      ranges(0) should be(0 to 255)
      ranges(1) should be(256 to 511)
      ranges(2) should be(512 to 767)
      ranges(3) should be(768 to 1023)

      val allEnvelopes =
        (0 until 4).flatMap { rangeIndex =>
          val result =
            query
              .currentEventsBySlices[String](entityType, ranges(rangeIndex).min, ranges(rangeIndex).max, NoOffset)
              .runWith(Sink.seq)
              .futureValue
          result.foreach { env =>
            ranges(rangeIndex) should contain(query.sliceForPersistenceId(env.persistenceId))
          }
          result
        }
      allEnvelopes.size should be(numberOfPersisters * numberOfEvents)
    }
  }

  // tests just relevant for live query
  "Live eventsBySlices" should {
    "find new events" in new Setup {
      for (i <- 1 to 20) {
        persister ! PersistWithAck(s"e-$i", probe.ref)
        probe.expectMessage(Done)
      }
      val result: TestSubscriber.Probe[EventEnvelope[String]] =
        query.eventsBySlices[String](entityType, slice, slice, NoOffset).runWith(sinkProbe).request(21)
      for (i <- 1 to 20) {
        result.expectNext().event shouldBe s"e-$i"
      }

      for (i <- 21 to 40) {
        persister ! PersistWithAck(s"e-$i", probe.ref)
        // make sure the query doesn't get an element in its buffer with nothing to take it
        // resulting in it not finishing the query and giving up the session
        result.request(1)
        probe.expectMessage(Done)
      }

      result.request(1)

      for (i <- 21 to 40) {
        result.expectNext().event shouldBe s"e-$i"
      }

      result.cancel()
    }

    "retrieve from several slices" in new Setup {
      val numberOfPersisters = 20
      val numberOfEvents = 3

      persistenceExt.numberOfSlices should be(1024)
      val ranges = query.sliceRanges(4)
      ranges(0) should be(0 to 255)
      ranges(1) should be(256 to 511)
      ranges(2) should be(512 to 767)
      ranges(3) should be(768 to 1023)

      val queries: Seq[Source[EventEnvelope[String], NotUsed]] =
        (0 until 4).map { rangeIndex =>
          query
            .eventsBySlices[String](entityType, ranges(rangeIndex).min, ranges(rangeIndex).max, NoOffset)
            .map { env =>
              ranges(rangeIndex) should contain(query.sliceForPersistenceId(env.persistenceId))
              env
            }
        }
      val allEnvelopes =
        queries(0)
          .merge(queries(1))
          .merge(queries(2))
          .merge(queries(3))
          .take(numberOfPersisters * numberOfEvents)
          .runWith(Sink.seq[EventEnvelope[String]])

      val persistenceIds = (1 to numberOfPersisters).map(_ => nextPid(entityType)).toVector
      val persisters = persistenceIds.map { pid =>
        val ref = testKit.spawn(TestActors.Persister(pid))
        for (i <- 1 to numberOfEvents) {
          ref ! PersistWithAck(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }
        ref
      }

      allEnvelopes.futureValue.size should be(numberOfPersisters * numberOfEvents)
    }
  }

}
