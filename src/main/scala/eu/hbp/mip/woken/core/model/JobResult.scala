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

package eu.hbp.mip.woken.core.model

import java.time.OffsetDateTime

import eu.hbp.mip.woken.core.model.Shapes.{ pfa => pfaShape, _ }
import eu.hbp.mip.woken.messages.query.{ AlgorithmSpec, QueryResult, queryProtocol }
import spray.json._

sealed trait JobResult extends Product with Serializable {
  def jobId: String

  def node: String

  def timestamp: OffsetDateTime

  def algorithm: String
}

case class PfaJobResult(jobId: String,
                        node: String,
                        timestamp: OffsetDateTime,
                        algorithm: String,
                        model: JsObject)
    extends JobResult {

  def injectCell(name: String, value: JsValue): PfaJobResult = {
    val cells        = model.fields.getOrElse("cells", JsObject()).asJsObject
    val updatedCells = JsObject(cells.fields + (name -> value))
    val updatedModel = JsObject(model.fields + ("cells" -> updatedCells))

    copy(model = updatedModel)
  }

}

case class PfaExperimentJobResult(jobId: String,
                                  node: String,
                                  timestamp: OffsetDateTime,
                                  models: JsArray)
    extends JobResult {

  override val algorithm = "experiment"
}

object PfaExperimentJobResult {

  def apply(results: Map[AlgorithmSpec, JobResult],
            experimentJobId: String,
            experimentNode: String): PfaExperimentJobResult = {

    implicit val offsetDateTimeJsonFormat: RootJsonFormat[OffsetDateTime] =
      queryProtocol.OffsetDateTimeJsonFormat

    // Concatenate results while respecting received algorithms order
    val output = JsArray(
      results
        .map(r => {
          val code = r._1.code
          r._2 match {
            case PfaJobResult(jobId, node, timestamp, algorithm, model) =>
              // TODO: inform if algorithm is predictive...
              JsObject(
                "type"      -> JsString(pfaShape.mime),
                "algorithm" -> JsString(algorithm),
                "code"      -> JsString(code),
                "jobId"     -> JsString(jobId),
                "node"      -> JsString(node),
                "timestamp" -> timestamp.toJson,
                "data"      -> model
              )
            case ErrorJobResult(jobId, node, timestamp, algorithm, errorMsg) =>
              JsObject(
                "type"      -> JsString(error.mime),
                "algorithm" -> JsString(algorithm),
                "code"      -> JsString(code),
                "jobId"     -> JsString(jobId),
                "node"      -> JsString(node),
                "timestamp" -> timestamp.toJson,
                "error"     -> JsString(errorMsg)
              )
            case JsonDataJobResult(jobId, node, timestamp, shape, algorithm, data) =>
              JsObject(
                "type"      -> JsString(shape),
                "algorithm" -> JsString(algorithm),
                "code"      -> JsString(code),
                "jobId"     -> JsString(jobId),
                "node"      -> JsString(node),
                "timestamp" -> timestamp.toJson,
                "data"      -> data
              )
            case OtherDataJobResult(jobId, node, timestamp, shape, algorithm, data) =>
              JsObject(
                "type"      -> JsString(shape),
                "algorithm" -> JsString(algorithm),
                "code"      -> JsString(code),
                "jobId"     -> JsString(jobId),
                "node"      -> JsString(node),
                "timestamp" -> timestamp.toJson,
                "data"      -> JsString(data)
              )
            case PfaExperimentJobResult(jobId, node, timestamp, models) =>
              JsObject(
                "type"      -> JsString(Shapes.pfaExperiment.mime),
                "code"      -> JsString("experiment"),
                "jobId"     -> JsString(jobId),
                "node"      -> JsString(node),
                "timestamp" -> timestamp.toJson,
                "models"    -> models
              )
          }
        })
        .toVector
    )

    PfaExperimentJobResult(
      jobId = experimentJobId,
      node = experimentNode,
      timestamp = OffsetDateTime.now(),
      models = output
    )
  }
}

case class ErrorJobResult(jobId: String,
                          node: String,
                          timestamp: OffsetDateTime,
                          algorithm: String,
                          error: String)
    extends JobResult

sealed trait VisualisationJobResult extends JobResult {
  def shape: String
}

case class JsonDataJobResult(jobId: String,
                             node: String,
                             timestamp: OffsetDateTime,
                             shape: String,
                             algorithm: String,
                             data: JsValue)
    extends VisualisationJobResult

case class DataResourceJobResult(jobId: String,
                                 node: String,
                                 timestamp: OffsetDateTime,
                                 shape: String,
                                 algorithm: String,
                                 data: JsValue)
    extends VisualisationJobResult

case class OtherDataJobResult(jobId: String,
                              node: String,
                              timestamp: OffsetDateTime,
                              shape: String,
                              algorithm: String,
                              data: String)
    extends VisualisationJobResult

object JobResult {

  def asQueryResult(jobResult: JobResult): QueryResult =
    jobResult match {
      case pfa: PfaJobResult =>
        QueryResult(
          jobId = pfa.jobId,
          node = pfa.node,
          timestamp = pfa.timestamp,
          shape = pfaShape.mime,
          algorithm = pfa.algorithm,
          data = Some(pfa.model),
          error = None
        )
      case pfa: PfaExperimentJobResult =>
        QueryResult(
          jobId = pfa.jobId,
          node = pfa.node,
          timestamp = pfa.timestamp,
          shape = pfaExperiment.mime,
          algorithm = pfa.algorithm,
          data = Some(pfa.models),
          error = None
        )
      case v: JsonDataJobResult =>
        QueryResult(
          jobId = v.jobId,
          node = v.node,
          timestamp = v.timestamp,
          shape = v.shape,
          algorithm = v.algorithm,
          data = Some(v.data),
          error = None
        )
      case v: DataResourceJobResult =>
        QueryResult(
          jobId = v.jobId,
          node = v.node,
          timestamp = v.timestamp,
          shape = v.shape,
          algorithm = v.algorithm,
          data = Some(v.data),
          error = None
        )
      case v: OtherDataJobResult =>
        QueryResult(
          jobId = v.jobId,
          node = v.node,
          timestamp = v.timestamp,
          shape = v.shape,
          algorithm = v.algorithm,
          data = Some(JsString(v.data)),
          error = None
        )
      case e: ErrorJobResult =>
        QueryResult(
          jobId = e.jobId,
          node = e.node,
          timestamp = e.timestamp,
          shape = error.mime,
          algorithm = e.algorithm,
          data = None,
          error = Some(e.error)
        )
    }

  implicit class ToQueryResult(val jobResult: JobResult) extends AnyVal {
    def asQueryResult: QueryResult = JobResult.asQueryResult(jobResult)
  }

}
