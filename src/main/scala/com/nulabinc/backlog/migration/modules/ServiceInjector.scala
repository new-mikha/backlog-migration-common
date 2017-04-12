package com.nulabinc.backlog.migration.modules

import com.google.inject.{AbstractModule, Guice, Injector}
import com.nulabinc.backlog.migration.conf.{BacklogApiConfiguration, BacklogPaths}
import com.nulabinc.backlog.migration.service._
import com.nulabinc.backlog4j.conf.BacklogPackageConfigure
import com.nulabinc.backlog4j.{BacklogClient, BacklogClientFactory}

/**
  * @author uchida
  */
object ServiceInjector {

  def createInjector(apiConfig: BacklogApiConfiguration): Injector = {
    Guice.createInjector(new AbstractModule() {
      override def configure(): Unit = {
        val backlogPackageConfigure = new BacklogPackageConfigure(apiConfig.url)
        val configure               = backlogPackageConfigure.apiKey(apiConfig.key)
        val backlog                 = new BacklogClientFactory(configure).newClient()

        bind(classOf[BacklogClient]).toInstance(backlog)
        bind(classOf[ProjectService]).to(classOf[ProjectServiceImpl])
        bind(classOf[SpaceService]).to(classOf[SpaceServiceImpl])
        bind(classOf[UserService]).to(classOf[UserServiceImpl])
        bind(classOf[StatusService]).to(classOf[StatusServiceImpl])
        bind(classOf[PriorityService]).to(classOf[PriorityServiceImpl])
        bind(classOf[BacklogPaths]).toInstance(new BacklogPaths(apiConfig.projectKey))
      }
    })
  }

}
