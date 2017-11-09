/*
 * Copyright 2017 LREN CHUV
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

package eu.hbp.mip.woken.api

import akka.actor.ActorSystem
import spray.routing.{ HttpService, Route }
import eu.hbp.mip.woken.config.{
  FederationDatabaseConfig,
  LdsmDatabaseConfig,
  ResultDatabaseConfig
}
import eu.hbp.mip.woken.core.{ Core, CoreActors }

/**
  * The REST API layer. It exposes the REST services, but does not provide any
  * web server interface.<br/>
  * Notice that it requires to be mixed in with ``core.CoreActors``, which provides access
  * to the top-level actors that make up the system.
  */
trait Api extends HttpService with CoreActors with Core {

  protected implicit val system: ActorSystem

  val job_service = new JobService(chronosHttp,
                                   ResultDatabaseConfig.dal,
                                   FederationDatabaseConfig.config.map(_.dal),
                                   LdsmDatabaseConfig.dal)

  val mining_service = new MiningService(chronosHttp,
                                         ResultDatabaseConfig.dal,
                                         FederationDatabaseConfig.config.map(_.dal),
                                         LdsmDatabaseConfig.dal)

  val routes: Route = new SwaggerService().routes ~
  job_service.routes ~
  mining_service.routes

}
