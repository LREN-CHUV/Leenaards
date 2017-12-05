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
import eu.hbp.mip.woken.backends.{ DockerJob, QueryOffset }
import eu.hbp.mip.woken.backends.chronos.ChronosService
import eu.hbp.mip.woken.backends.chronos.{ ChronosJob, JobToChronos }
import eu.hbp.mip.woken.config.{ DbConnectionConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.dao.{ FeaturesDAL, JobResultsDAL }
import spray.json.{ JsonFormat, RootJsonFormat }

// TODO: jobResultsFactory does serialisation in strange ways depending on the needs of the client. This is applied too early and should be removed
case class CoordinatorConfig(chronosService: ActorRef,
                             featuresDatabase: FeaturesDAL,
                             resultDatabase: JobResultsDAL,
                             jobResultsFactory: JobResults.Factory,
                             dockerBridgeNetwork: Option[String],
                             jobsConf: JobsConfiguration,
                             jdbcConfF: String => Validation[DbConnectionConfiguration])

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
  private[CoordinatorActor] object CheckChronos

  // Responses

  type Result = eu.hbp.mip.woken.core.model.JobResult
  val Result: JobResult.type = eu.hbp.mip.woken.core.model.JobResult

  // TODO: duplicates Error class
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

  case object SubmittedJobToChronos extends State

  case object RequestFinalResult extends State

  /** Called when we know from Chronos that the job is complete yet results have not appeared yet in the database */
  case object ExpectFinalResult extends State

  // FSM Data

  trait StateData {
    def job: DockerJob
    def replyTo: ActorRef
  }

  case object Uninitialized extends StateData {
    def job     = throw new IllegalAccessException()
    def replyTo = throw new IllegalAccessException()
  }

  case class PartialLocalData(job: DockerJob,
                              chronosJob: ChronosJob,
                              replyTo: ActorRef,
                              pollDbCount: Int,
                              timeoutTime: Long)
      extends StateData

  case class ExpectedLocalData(job: DockerJob,
                               chronosJob: ChronosJob,
                               replyTo: ActorRef,
                               pollDbCount: Int,
                               timeoutTime: Long)
      extends StateData
}

// TODO: Chronos can call a callback url when a job is complete, use that functionality

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of making two requests and then aggregating them together:
  *   - One request to Chronos to start the job
  *   - Then a separate request in the database for the results, repeated until enough results are present
  *
  *  _________________           _______________________                   ______________________
  * |                 | Start   |                       | Even(Ok, data)   |                    |
  * | WaitForNewJob   | ------> | SubmittedJobToChronos |----------------> | RequestFinalResult | ==> results
  * | (Uninitialized) |         | (PartialLocalData)    |                  | (PartialLocalData) |
  *  -----------------           -----------------------                    --------------------
  *
  */
class CoordinatorActor(
    val chronosService: ActorRef,
    val resultDatabase: JobResultsDAL,
    val jobResultsFactory: JobResults.Factory,
    dockerBridgeNetwork: Option[String],
    jobsConf: JobsConfiguration,
    jdbcConfF: String => Validation[DbConnectionConfiguration]
) extends Actor
    with ActorLogging
    with ActorTracing
    with LoggingFSM[CoordinatorStates.State, CoordinatorStates.StateData] {

  import CoordinatorActor._
  import CoordinatorStates._

  val repeatDuration: FiniteDuration = 200.milliseconds
  val startTime: Long                = System.currentTimeMillis

  startWith(WaitForNewJob, Uninitialized)
  log.info("Local coordinator actor started...")

  when(WaitForNewJob) {
    case Event(Start(job), Uninitialized) =>
      val replyTo = sender()

      import ChronosService._
      val chronosJob: Validation[ChronosJob] =
        JobToChronos(job, dockerBridgeNetwork, jobsConf, jdbcConfF)

      chronosJob.fold[State](
        { err =>
          val msg = err.toList.mkString
          replyTo ! Error(msg)
          stop(Failure(msg))
        }, { cj =>
          chronosService ! Schedule(cj)
          log.info(s"Wait for Chronos to fulfill job ${job.jobId}, will reply to $replyTo")
          goto(SubmittedJobToChronos) using PartialLocalData(
            job,
            chronosJob = cj,
            replyTo,
            0,
            System.currentTimeMillis + 1.day.toMillis
          )
        }
      )

  }

  // Process the response to the POST request sent to Chronos
  when(SubmittedJobToChronos) {

    case Event(ChronosService.Ok, data: PartialLocalData) =>
      log.info(s"Job ${data.job.jobId} posted to Chronos")
      goto(RequestFinalResult) using data

    case Event(e: ChronosService.Error, data: PartialLocalData) =>
      val msg =
        s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, received error: ${e.message}"
      log.error(msg)
      data.replyTo ! ErrorResponse(msg)
      stop(Failure(msg))

    case Event(_: Timeout @unchecked, data: PartialLocalData) =>
      val msg =
        s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, timeout while connecting to Chronos"
      log.error(msg)
      data.replyTo ! ErrorResponse(msg)
      stop(Failure(msg))
  }

  // Track job status until its completion
  when(RequestFinalResult, stateTimeout = repeatDuration) {

    // Handle scheduled ticks
    case Event(StateTimeout, data: PartialLocalData) =>
      if (System.currentTimeMillis > data.timeoutTime) {
        val msg =
          s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, job timed out"
        log.error(msg)
        data.replyTo ! ErrorResponse(msg)
        stop(Failure(msg))
      } else {
        self ! CheckDb
        if (data.pollDbCount % 50 == 0) {
          self ! CheckChronos
        }
        stay() forMax repeatDuration
      }

    // Check the database for the job result; prepare the next tick or send back the response if the job completed
    case Event(CheckDb, data: PartialLocalData) =>
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        log.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! jobResultsFactory(results)
        log.info("Stopping...")
        stop(Normal)
      } else {
        stay() using data.copy(pollDbCount = data.pollDbCount + 1) forMax repeatDuration
      }

    // Check Chronos for the job status; prepare the next tick
    case Event(CheckChronos, data: PartialLocalData) =>
      chronosService ! ChronosService.Check(data.job.jobId, data.chronosJob)
      stay() forMax repeatDuration

    // Handle Chronos responses
    case Event(ChronosService.JobComplete(jobId, success), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        log.warning(
          s"Chronos returned job complete for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        log.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! jobResultsFactory(results)

        val reportedSuccess = results.foldRight(true)((res, suc) => suc && res.error.isEmpty)
        if (reportedSuccess != success) {
          log.warning(
            s"Chronos reported that job ${data.job.jobId} using Docker image ${data.job.dockerImage} is ${if (!success)
              "not "}successful, however the job results ${if (reportedSuccess) "do not "}contain an error"
          )
        }
        log.info("Stopping...")
        stop(Normal)
      } else {
        goto(ExpectFinalResult) using ExpectedLocalData(
          data.job,
          data.chronosJob,
          data.replyTo,
          0,
          System.currentTimeMillis + 1.minute.toMillis
        )
      }

    case Event(ChronosService.JobNotFound(jobId), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        log.warning(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      val msg =
        s"Chronos lost track of job ${data.job.jobId} using ${data.job.dockerImage}, it may have been stopped manually"
      log.error(msg)
      data.replyTo ! ErrorResponse(msg)
      stop(Failure(msg))

    case Event(ChronosService.JobQueued(jobId), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        log.warning(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      // Nothing more to do, wait
      stay() forMax repeatDuration

    case Event(ChronosService.JobUnknownStatus(jobId, status), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        log.warning(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      log.warning(
        s"Chronos reported status $status for job ${data.job.jobId} using ${data.job.dockerImage}"
      )
      // Nothing more to do, wait
      stay() forMax repeatDuration

    case Event(ChronosService.ChronosUnresponsive(jobId, error), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        log.warning(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      log.warning(
        s"Chronos appear unresponsive with error $error while checking job ${data.job.jobId} using ${data.job.dockerImage}"
      )
      // TODO: if Chronos is down for too long, enter panic state!
      // Nothing more to do, wait
      stay() forMax repeatDuration
  }

  when(ExpectFinalResult, stateTimeout = repeatDuration) {

    // Handle scheduled ticks
    case Event(StateTimeout, data: ExpectedLocalData) =>
      if (System.currentTimeMillis > data.timeoutTime) {
        val msg =
          s"Cannot complete job ${data.job.jobId} using ${data.job.dockerImage}, time out while waiting for job results"
        log.error(msg)
        data.replyTo ! ErrorResponse(msg)
        stop(Failure(msg))
      } else {
        self ! CheckDb
        stay() forMax repeatDuration
      }

    // Check the database for the job result; prepare the next tick or send back the response if the job completed
    case Event(CheckDb, data: ExpectedLocalData) =>
      val results = resultDatabase.findJobResults(data.job.jobId)
      if (results.nonEmpty) {
        log.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! jobResultsFactory(results)
        log.info("Stopping...")
        stop(Normal)
      } else {
        stay() using data.copy(pollDbCount = data.pollDbCount + 1) forMax repeatDuration
      }

  }

  whenUnhandled {
    case Event(e, s) =>
      log.warning(s"Received unhandled request $e of type ${e.getClass} in state $stateName/$s")
      stay
  }

  def transitions: TransitionHandler = {
    case SubmittedJobToChronos -> RequestFinalResult =>
      self ! CheckDb
  }

  onTransition(transitions)

  onTermination {
    // TODO: all jobs should be cleaned from Chronos after completion, but we keep the success for now for reporting
    //case StopEvent(FSM.Normal, RequestFinalResult | ExpectFinalResult, data) => chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Shutdown, _, data: PartialLocalData) =>
      chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Shutdown, _, data: ExpectedLocalData) =>
      chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Failure(_), RequestFinalResult, data: PartialLocalData) =>
      chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Failure(_), ExpectFinalResult, data: ExpectedLocalData) =>
      chronosService ! ChronosService.Cleanup(data.chronosJob)
  }

  initialize()
}

/*

  case class PartialNodesData(job: DockerJob,
                              replyTo: ActorRef,
                              remainingNodes: Set[String] = Set(),
                              totalNodeCount: Int)
      extends StateData


class FederationCoordinatorActor(val chronosService: ActorRef,
                                 val resultDatabase: JobResultsDAL,
                                 val federationDatabase: JobResultsDAL,
                                 val jobResultsFactory: JobResults.Factory)
    extends CoordinatorActor {

  import CoordinatorActor._
  import CoordinatorStates._

  when(WaitForNewJob) {

    case Event(Start(job), Uninitialized) =>
      import eu.hbp.mip.woken.config.WokenConfig
      val replyTo = sender()
      val nodes   = job.nodes.filter(_.isEmpty).getOrElse(WokenConfig.jobs.nodes)

      log.warning(s"List of nodes: ${nodes.mkString(",")}")

      if (nodes.nonEmpty) {
        for (node <- nodes) {
          val workerNode = context.actorOf(Props(classOf[JobClientService], node))
          workerNode ! Start(job.copy(nodes = None))
        }
        goto(WaitForNodes) using PartialNodesData(job, replyTo, nodes, nodes.size)
      } else {
        goto(SubmittedJobToChronos) using PartialLocalData(job, replyTo)
      }
  }

  // TODO: implement a reconciliation algorithm: http://mesos.apache.org/documentation/latest/reconciliation/
  when(WaitForNodes) {

    case Event(WorkerJobComplete(node), data: PartialNodesData) =>
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }

    case Event(WorkerJobError(node, message), data: PartialNodesData) =>
      log.error(message)
      if (data.remainingNodes == Set(node)) {
        goto(RequestIntermediateResults) using data.copy(remainingNodes = Set())
      } else {
        goto(WaitForNodes) using data.copy(remainingNodes = data.remainingNodes - node)
      }
  }

  when(RequestIntermediateResults, stateTimeout = repeatDuration) {

    case Event(StateTimeout, data: PartialNodesData) =>
      val results = federationDatabase.findJobResults(data.job.jobId)
      if (results.size == data.totalNodeCount) {
        data.job.federationDockerImage.fold {
          data.replyTo ! PutJobResults(results)
          stop()
        } { federationDockerImage =>
          val parameters = Map(
            "PARAM_query" -> s"select data from job_result_nodes where job_id='${data.job.jobId}'"
          )
          goto(SubmittedJobToChronos) using PartialLocalData(
            data.job.copy(dockerImage = federationDockerImage, parameters = parameters),
            data.replyTo
          )
        }
      } else {
        stay() forMax repeatDuration
      }
  }

  initialize()

}

 */
