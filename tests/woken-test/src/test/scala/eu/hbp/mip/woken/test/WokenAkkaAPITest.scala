/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.test

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import eu.hbp.mip.woken.messages.query._
import eu.hbp.mip.woken.messages.variables.VariableId
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.scalatest.TryValues._
import org.scalatest.tagobjects.Slow

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class WokenAkkaAPITest
    extends FlatSpec
    with Matchers
    with Queries
    with BeforeAndAfterAll {

  implicit val timeout: Timeout = Timeout(200 seconds)
  val configuration: Config = ConfigFactory.load()
  val system: ActorSystem = ActorSystem("test", configuration)
  implicit val ec: ExecutionContext = system.dispatcher

  val client: ActorRef =
    system.actorOf(ClusterClient.props(ClusterClientSettings(system)), "client")
  val entryPoint = "/user/entrypoint"

  override def afterAll: Unit = {
    system.terminate().onComplete { result =>
      println("Actor system shutdown: " + result)
    }
  }

  // Test methods query
  "Woken" should "respond to a query for the list of methods" in {

    val start = System.currentTimeMillis()
    val future = client ? ClusterClient.Send(entryPoint,
                                             MethodsQuery,
                                             localAffinity = true)
    val result = waitFor[MethodsResponse](future)
    val end = System.currentTimeMillis()

    println(
      "List of methods query complete in " + Duration(end - start,
                                                      TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.methods shouldNot have size 0
  }

  // Test mining query
  "Woken" should "respond to a data mining query" in {

    val start = System.currentTimeMillis()
    val query = MiningQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables = List(VariableId("score_math_course1")),
      grouping = Nil,
      filters = None,
      targetTable = Some("sample_data"),
      algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5"))),
      datasets = Set(),
      executionPlan = None
    )

    val future = client ? ClusterClient.Send(entryPoint,
                                             query,
                                             localAffinity = true)
    val result = waitFor[QueryResult](future)
    val end = System.currentTimeMillis()

    println(
      "Data mining query complete in " + Duration(end - start,
                                                  TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.data should not be empty

    val json = result.success.value.data.get
    val expected = loadJson("/knn_data_mining.json")

    assertResult(approximate(expected))(approximate(json))
  }

  "Woken" should "respond to a data mining query with visualisation" in {

    val start = System.currentTimeMillis()
    val query = MiningQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables =
        List("score_math_course1", "score_math_course2").map(VariableId),
      grouping = Nil,
      filters = None,
      targetTable = Some("sample_data"),
      algorithm = AlgorithmSpec("histograms", Nil),
      datasets = Set(),
      executionPlan = None
    )

    val future = client ? ClusterClient.Send(entryPoint,
                                             query,
                                             localAffinity = true)
    val result = waitFor[QueryResult](future)
    val end = System.currentTimeMillis()

    println(
      "Data mining query with visualisation complete in " + Duration(
        end - start,
        TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.data should not be empty

    val json = result.success.value.data.get
    val expected = loadJson("/histograms.json")

    assertResult(approximate(expected))(approximate(json))
  }

  // Test experiment query
  "Woken" should "respond to an experiment query" in {

    val start = System.currentTimeMillis()
    val query = experimentQuery("knn", List(CodeValue("k", "5")))
    val future = client ? ClusterClient.Send(entryPoint,
                                             query,
                                             localAffinity = true)
    val result = waitFor[QueryResult](future)
    val end = System.currentTimeMillis()

    println(
      "Experiment query complete in " + Duration(end - start,
                                                 TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    val data = result.success.value.data

    data should not be empty

    val json = data.get
    val expected = loadJson("/knn_experiment.json")

    assertResult(approximate(expected))(approximate(json))
  }

  //Test resiliency
  "Woken" should "recover from multiple failed experiments" taggedAs Slow in {

    // TODO: add no_results, never_end
    val failures = List("training_fails",
                        "invalid_json",
                        "invalid_pfa_syntax",
                        "invalid_pfa_semantics")

    val queries = failures.map(failure =>
      experimentQuery("chaos", List(CodeValue("failure", failure))))

    val futures = queries.map(query =>
      client ? ClusterClient.Send(entryPoint, query, localAffinity = true))

    futures.foreach { f =>
      println("Waiting for result from chaos algorithm...")
      val result = waitFor[QueryResult](f)
      if (result.isFailure) {
        println(s"Chaos algorithm failed with ${result.failed.get}")
      } else {
        println(s"Chaos algorithm returned ${result.success.value}")
      }
    }

    val knnQuery = experimentQuery("knn", List(CodeValue("k", "5")))
    val successfulFuture = client ? ClusterClient.Send(entryPoint,
                                                       knnQuery,
                                                       localAffinity = true)
    val result = waitFor[QueryResult](successfulFuture)

    if (!result.isSuccess) {
      println(result)
    }

    val data = result.success.value.data

    data should not be empty

    val json = data.get
    val expected = loadJson("/knn_experiment.json")

    assertResult(approximate(expected))(approximate(json))

  }

  private def waitFor[T](future: Future[Any])(
      implicit timeout: Timeout): Try[T] = {
    Try {
      Await.result(future, timeout.duration).asInstanceOf[T]
    }
  }

}
