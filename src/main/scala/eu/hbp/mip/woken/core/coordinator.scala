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

package eu.hbp.mip.woken.core

import akka.actor.FSM.{ Failure, Normal }
import akka.actor._
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.api.ApiJsonSupport
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller

import scala.concurrent.duration._
import eu.hbp.mip.woken.core.CoordinatorActor.Start
import eu.hbp.mip.woken.backends.{ DockerJob, QueryOffset }
import eu.hbp.mip.woken.backends.chronos.ChronosService
import eu.hbp.mip.woken.backends.chronos.{ ChronosJob, JobToChronos }
import eu.hbp.mip.woken.config.{ JdbcConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.dao.JobResultsDAL
import spray.json.{ JsonFormat, RootJsonFormat }

case class CoordinatorConfig(chronosService: ActorRef,
                             resultDatabase: JobResultsDAL,
                             jobResultsFactory: JobResults.Factory,
                             dockerBridgeNetwork: Option[String],
                             jobsConf: JobsConfiguration,
                             jdbcConfF: String => Validation[JdbcConfiguration])

/**
  * We use the companion object to hold all the messages that the ``CoordinatorActor``
  * receives.
  */
object CoordinatorActor {

  // Incoming messages
  case class Start(job: DockerJob) extends RestMessage {
    import ApiJsonSupport._
    import spray.httpx.SprayJsonSupport._
    implicit val queryOffsetFormat: RootJsonFormat[QueryOffset] = jsonFormat2(QueryOffset.apply)
    implicit val jobFormat: RootJsonFormat[DockerJob]           = jsonFormat7(DockerJob.apply)
    override def marshaller: ToResponseMarshaller[Start] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(jsonFormat1(Start))
  }

  // Internal messages
  private[CoordinatorActor] object CheckDb

  // Responses

  type Result = eu.hbp.mip.woken.core.model.JobResult
  val Result = eu.hbp.mip.woken.core.model.JobResult

  case class ErrorResponse(message: String) extends RestMessage {
    import spray.httpx.SprayJsonSupport._
    import spray.json.DefaultJsonProtocol._
    override def marshaller: ToResponseMarshaller[ErrorResponse] =
      ToResponseMarshaller.fromMarshaller(StatusCodes.InternalServerError)(
        jsonFormat1(ErrorResponse)
      )
  }

  import JobResult._
  implicit val resultFormat: JsonFormat[Result]                   = JobResult.jobResultFormat
  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)

  def props(coordinatorConfig: CoordinatorConfig): Props =
    Props(
      new CoordinatorActor(
        coordinatorConfig.chronosService,
        coordinatorConfig.resultDatabase,
        coordinatorConfig.jobResultsFactory,
        coordinatorConfig.dockerBridgeNetwork,
        coordinatorConfig.jobsConf,
        coordinatorConfig.jdbcConfF
      )
    )

  def actorName(job: DockerJob): String =
    s"LocalCoordinatorActor_job_${job.jobId}_${job.jobName}"

}

/** FSM States and internal data */
object CoordinatorStates {

  // FSM States

  sealed trait State

  case object WaitForNewJob extends State

  case object PostJobToChronos extends State

  case object RequestFinalResult extends State

  // FSM Data

  trait StateData {
    def job: DockerJob
    def replyTo: ActorRef
  }

  case object Uninitialized extends StateData {
    def job     = throw new IllegalAccessException()
    def replyTo = throw new IllegalAccessException()
  }

  case class PartialNodesData(job: DockerJob,
                              replyTo: ActorRef,
                              remainingNodes: Set[String] = Set(),
                              totalNodeCount: Int)
      extends StateData

  case class PartialLocalData(job: DockerJob, replyTo: ActorRef) extends StateData

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of making two requests and then aggregating them together:
  *  - One request to Chronos to start the job
  *  - Then a separate request in the database for the results, repeated until enough results are present
  *
  *  _________________           ____________________                    ____________________
  * |                 | Start   |                    | Even(Ok, data)   |                    |
  * | WaitForNewJob   | ------> | PostJobToChronos   |----------------> | RequestFinalResult | ==> results
  * | (Uninitialized) |         | (PartialLocalData) |                  |                    |
  *  -----------------           --------------------                    --------------------
  *
  */
class CoordinatorActor(
    val chronosService: ActorRef,
    val resultDatabase: JobResultsDAL,
    val jobResultsFactory: JobResults.Factory,
    dockerBridgeNetwork: Option[String],
    jobsConf: JobsConfiguration,
    jdbcConfF: String => Validation[JdbcConfiguration]
) extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[CoordinatorStates.State, CoordinatorStates.StateData] {

  import CoordinatorStates._

  val repeatDuration: FiniteDuration = 200.milliseconds
  val startTime: Long                = System.currentTimeMillis

  startWith(WaitForNewJob, Uninitialized)
  log.info("Local coordinator actor started...")

  when(WaitForNewJob) {
    case Event(Start(job), Uninitialized) =>
      val replyTo = sender()
      log.info(s"Wait for Chronos to fulfill job ${job.jobId}, will reply to $replyTo")
      goto(PostJobToChronos) using PartialLocalData(job, replyTo)
  }

  when(PostJobToChronos) {

    case Event(Ok, data: PartialLocalData) =>
      log.info(s"Job ${data.job.jobId} posted to Chronos")
      goto(RequestFinalResult) using data

    case Event(e: Error, data: PartialLocalData) =>
      val msg =
        s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, received error: ${e.message}"
      log.error(msg)
      data.replyTo ! Error(msg)
      stop(Failure(msg))

    case Event(_: Timeout @unchecked, data: PartialLocalData) =>
      val msg =
        s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, timeout while connecting to Chronos"
      log.error(msg)
      data.replyTo ! Error(msg)
      stop(Failure(msg))
  }

  when(RequestFinalResult, stateTimeout = repeatDuration) {

    case Event(StateTimeout, data: PartialLocalData) =>
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        log.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! jobResultsFactory(results)
        log.info("Stopping...")
        stop(Normal)
      } else {
        stay() forMax repeatDuration
      }
  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning("Received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  def transitions: TransitionHandler = {

    case _ -> PostJobToChronos =>
      import ChronosService._
      val chronosJob: Validation[ChronosJob] =
        JobToChronos(nextStateData.job, dockerBridgeNetwork, jobsConf, jdbcConfF)
      chronosJob.fold[Unit]({ err =>
        nextStateData.replyTo ! Error(err.toList.mkString)
      }, { job =>
        chronosService ! Schedule(job)
      })

  }

  onTransition(transitions)

  initialize()
}