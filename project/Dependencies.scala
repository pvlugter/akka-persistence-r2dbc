/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

import sbt._

object Dependencies {
  val Scala213 = "2.13.15"
  val Scala3 = "3.3.4"
  val Scala2Versions = Seq(Scala213)
  val ScalaVersions = Dependencies.Scala2Versions :+ Dependencies.Scala3
  val AkkaVersion = System.getProperty("override.akka.version", "2.10.5")
  val AkkaVersionInDocs = VersionNumber(AkkaVersion).numbers match { case Seq(major, minor, _*) => s"$major.$minor" }
  val AkkaPersistenceJdbcVersion = "5.5.2" // only in migration tool tests
  val AkkaProjectionVersionInDocs = "current"
  val H2Version = "2.2.224"
  val R2dbcH2Version = "1.0.0.RELEASE"
  val SqlServerR2dbcVersion = "1.0.2.RELEASE"
  val SqlServerJdbcVersion = "7.4.1.jre8"
  // Java Platform version for JavaDoc creation
  // sync with Java version in .github/workflows/release.yml#documentation
  val JavaDocLinkVersion = 17

  object Compile {
    val akkaActorTyped = "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion
    val akkaStream = "com.typesafe.akka" %% "akka-stream" % AkkaVersion
    val akkaPersistence = "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion
    val akkaPersistenceQuery = "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion

    val r2dbcSpi = "io.r2dbc" % "r2dbc-spi" % "1.0.0.RELEASE" // ApacheV2
    val r2dbcPool = "io.r2dbc" % "r2dbc-pool" % "1.0.2.RELEASE" // ApacheV2
    val r2dbcPostgres = "org.postgresql" % "r2dbc-postgresql" % "1.0.7.RELEASE" // ApacheV2

    val h2 = "com.h2database" % "h2" % H2Version % Provided // EPL 1.0
    val r2dbcH2 = "io.r2dbc" % "r2dbc-h2" % R2dbcH2Version % Provided // ApacheV2

    val r2dbcSqlServer = "io.r2dbc" % "r2dbc-mssql" % SqlServerR2dbcVersion % Provided // ApacheV2
  }

  object TestDeps {
    val akkaStreamTyped = "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion % Test
    val akkaPersistenceTyped = "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion % Test
    val akkaShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion % Test
    val akkaPersistenceTck = "com.typesafe.akka" %% "akka-persistence-tck" % AkkaVersion % Test
    val akkaTestkit = "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test
    val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test
    val akkaJackson = "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion % Test

    val postgresql = "org.postgresql" % "postgresql" % "42.7.3" % Test // BSD-2-Clause

    val logback = "ch.qos.logback" % "logback-classic" % "1.5.18" % Test // EPL 1.0 / LGPL 2.1
    val scalaTest = "org.scalatest" %% "scalatest" % "3.2.12" % Test // ApacheV2
    val junit = "junit" % "junit" % "4.12" % Test // Eclipse Public License 1.0
    val junitInterface = "com.novocode" % "junit-interface" % "0.11" % Test // "BSD 2-Clause"
  }

  import Compile._

  val core = Seq(
    akkaPersistence,
    akkaPersistenceQuery,
    r2dbcSpi,
    r2dbcPool,
    r2dbcPostgres,
    h2,
    r2dbcH2,
    r2dbcSqlServer,
    TestDeps.akkaPersistenceTck,
    TestDeps.akkaStreamTestkit,
    TestDeps.akkaTestkit,
    TestDeps.akkaJackson,
    TestDeps.akkaStreamTyped,
    TestDeps.logback,
    TestDeps.scalaTest)

  val migrationTests =
    Seq(
      "com.lightbend.akka" %% "akka-persistence-jdbc" % AkkaPersistenceJdbcVersion % Test,
      "com.microsoft.sqlserver" % "mssql-jdbc" % SqlServerJdbcVersion % Test,
      TestDeps.postgresql,
      TestDeps.logback,
      TestDeps.scalaTest,
      h2,
      r2dbcH2,
      r2dbcSqlServer)

  val docs =
    Seq(
      // r2dbcPostgres is already a transitive dependency from core, but
      // sometimes sbt doesn't understand that ¯\_(ツ)_/¯
      r2dbcPostgres,
      TestDeps.akkaPersistenceTyped,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion)
}
