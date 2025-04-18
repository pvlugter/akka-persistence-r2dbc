/*
 * Copyright (C) 2022 - 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.state

import java.lang

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.persistence.r2dbc.TestConfig
import akka.persistence.r2dbc.TestData
import akka.persistence.r2dbc.TestDbLifecycle
import akka.persistence.r2dbc.state.scaladsl.AdditionalColumn
import akka.persistence.r2dbc.state.scaladsl.AdditionalColumn.BindNull
import akka.persistence.r2dbc.state.scaladsl.AdditionalColumn.BindValue
import akka.persistence.r2dbc.state.scaladsl.AdditionalColumn.Skip
import akka.persistence.r2dbc.state.scaladsl.R2dbcDurableStateStore
import akka.persistence.state.DurableStateStoreRegistry
import akka.persistence.state.scaladsl.GetObjectResult
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object DurableStateStoreAdditionalColumnSpec {
  val config: Config = ConfigFactory
    .parseString(s"""
    akka.persistence.r2dbc.state {
      custom-table {
        "CustomEntity" = durable_state_test
      }
      additional-columns {
        "CustomEntity" = ["${classOf[Column1].getName}", "${classOf[Column2].getName}", "${classOf[JavadslColumn].getName}"]
      }
    }
    """)
    .withFallback(TestConfig.config)

  class Column1 extends AdditionalColumn[String, String] {
    override def columnName: String = "col1"

    override def bind(upsert: AdditionalColumn.Upsert[String]): AdditionalColumn.Binding[String] =
      if (upsert.value.isEmpty) BindNull
      else if (upsert.value == "SKIP") Skip
      else BindValue(upsert.value)
  }

  class Column2 extends AdditionalColumn[String, Int] {
    override def columnName: String = "col2"

    override def bind(upsert: AdditionalColumn.Upsert[String]): AdditionalColumn.Binding[Int] =
      if (upsert.value.isEmpty) BindNull
      else if (upsert.value == "SKIP") Skip
      else BindValue(upsert.value.length)
  }
}

class DurableStateStoreAdditionalColumnSpec
    extends ScalaTestWithActorTestKit(DurableStateStoreAdditionalColumnSpec.config)
    with AnyWordSpecLike
    with TestDbLifecycle
    with TestData
    with LogCapturing {

  private def customTable(slice: Int) = settings.getDurableStateTableWithSchema("CustomEntity", slice)

  private def createCustomTable(slice: Int): String = {
    if (settings.dialectName == "sqlserver")
      s"IF object_id('${customTable(slice)}') is null SELECT * INTO ${customTable(slice)} FROM ${settings.durableStateTableWithSchema(slice)} where persistence_id = ''"
    else
      s"create table if not exists ${customTable(slice)} as select * from ${settings.durableStateTableWithSchema(slice)} where persistence_id = ''"
  }

  private def alterCustomTable(slice: Int, col: String, colType: String): String = {
    if (settings.dialectName == "sqlserver")
      s"IF COL_LENGTH('${customTable(slice)}', '$col') IS NULL ALTER TABLE ${customTable(slice)} ADD $col $colType"
    else
      s"alter table ${customTable(slice)} add if not exists $col $colType"
  }

  override def typedSystem: ActorSystem[_] = system

  override def beforeAll(): Unit = {
    settings.dataPartitionSliceRanges.foreach { sliceRange =>
      val dataPartitionSlice = sliceRange.min
      Await.result(
        r2dbcExecutor(dataPartitionSlice).executeDdl("beforeAll create durable_state_test")(
          _.createStatement(createCustomTable(dataPartitionSlice))),
        20.seconds)
      Await.result(
        r2dbcExecutor(dataPartitionSlice).executeDdl("beforeAll alter durable_state_test")(
          _.createStatement(alterCustomTable(dataPartitionSlice, "col1", "varchar(256)"))),
        20.seconds)
      Await.result(
        r2dbcExecutor(dataPartitionSlice).executeDdl("beforeAll alter durable_state_test")(
          _.createStatement(alterCustomTable(dataPartitionSlice, "col2", "int"))),
        20.seconds)
      Await.result(
        r2dbcExecutor(dataPartitionSlice).executeDdl("beforeAll alter durable_state_test")(
          _.createStatement(alterCustomTable(dataPartitionSlice, "col3", "int"))),
        20.seconds)
      Await.result(
        r2dbcExecutor(dataPartitionSlice).updateOne("beforeAll delete")(
          _.createStatement(s"delete from ${customTable(dataPartitionSlice)}")),
        10.seconds)
    }

    super.beforeAll()
  }

  private val store = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[R2dbcDurableStateStore[String]](R2dbcDurableStateStore.Identifier)

  private val unusedTag = "n/a"

  private def exists(slice: Int, whereCondition: String): Boolean =
    r2dbcExecutor(slice)
      .selectOne("count")(
        _.createStatement(s"select count(*) from ${customTable(slice)} where $whereCondition"),
        row => row.get(0, classOf[lang.Long]).longValue())
      .futureValue
      .contains(1)

  private def existsInCustomTable(persistenceId: String): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId'")
  }

  private def existsMatchingCol1(persistenceId: String, columnValue: String): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId' and col1 = '$columnValue'")
  }

  private def existsMatchingCol2(persistenceId: String, columnValue: Int): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId' and col2 = $columnValue")
  }

  private def existsMatchingCol3(persistenceId: String, columnValue: Int): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId' and col3 = $columnValue")
  }

  private def existsCol1IsNull(persistenceId: String): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId' and col1 is null")
  }

  private def existsCol2IsNull(persistenceId: String): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId' and col2 is null")
  }

  private def existsCol3IsNull(persistenceId: String): Boolean = {
    val slice = persistenceExt.sliceForPersistenceId(persistenceId)
    exists(slice, s"persistence_id = '$persistenceId' and col3 is null")
  }

  "The R2DBC durable state store" should {

    "save and retrieve a value in custom table with additional columns" in {
      val entityType = "CustomEntity"
      val persistenceId = nextPid(entityType)
      val value = "Genuinely Collaborative"

      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))

      existsInCustomTable(persistenceId) should be(true)
      existsMatchingCol1(persistenceId, value) should be(true)
      existsMatchingCol2(persistenceId, value.length) should be(true)
      existsMatchingCol3(persistenceId, value.length) should be(true)
    }

    "update a value in custom table with additional columns" in {
      val entityType = "CustomEntity"
      val persistenceId = nextPid(entityType)
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))

      val updatedValue = "Open to Feedback"
      store.upsertObject(persistenceId, 2L, updatedValue, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(updatedValue), 2L))

      existsInCustomTable(persistenceId) should be(true)
      existsMatchingCol1(persistenceId, value) should be(false)
      existsMatchingCol1(persistenceId, updatedValue) should be(true)
      existsMatchingCol2(persistenceId, value.length) should be(false)
      existsMatchingCol2(persistenceId, updatedValue.length) should be(true)
      existsMatchingCol3(persistenceId, value.length) should be(false)
      existsMatchingCol3(persistenceId, updatedValue.length) should be(true)
    }

    "support null binding of additional columns" in {
      val entityType = "CustomEntity"
      val persistenceId = nextPid(entityType)
      val emptyValue = ""
      store.upsertObject(persistenceId, 1L, emptyValue, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(emptyValue), 1L))
      existsCol1IsNull(persistenceId) should be(true)
      existsCol2IsNull(persistenceId) should be(true)
      existsCol3IsNull(persistenceId) should be(true)

      val updatedValue = "Open to Feedback"
      store.upsertObject(persistenceId, 2L, updatedValue, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(updatedValue), 2L))
      existsCol1IsNull(persistenceId) should be(false)
      existsCol2IsNull(persistenceId) should be(false)
      existsCol3IsNull(persistenceId) should be(false)

      store.upsertObject(persistenceId, 3L, emptyValue, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(emptyValue), 3L))
      existsCol1IsNull(persistenceId) should be(true)
      existsCol2IsNull(persistenceId) should be(true)
      existsCol3IsNull(persistenceId) should be(true)
    }

    "support skip binding of additional columns" in {
      val entityType = "CustomEntity"
      val persistenceId = nextPid(entityType)
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
      existsMatchingCol1(persistenceId, value) should be(true)
      existsMatchingCol2(persistenceId, value.length) should be(true)
      existsMatchingCol3(persistenceId, value.length) should be(true)

      val updatedValue = "SKIP"
      store.upsertObject(persistenceId, 2L, updatedValue, unusedTag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(updatedValue), 2L))
      // still same column values
      existsMatchingCol1(persistenceId, value) should be(true)
      existsMatchingCol2(persistenceId, value.length) should be(true)
      existsMatchingCol3(persistenceId, value.length) should be(true)
    }

  }

}
