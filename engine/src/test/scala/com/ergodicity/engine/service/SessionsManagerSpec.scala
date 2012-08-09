package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorRef, Props, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import org.mockito.Mockito._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.engine.Engine
import com.ergodicity.engine.Components.{OptInfoReplication, FutInfoReplication, CreateListener}
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener, ISubscriber}
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.service.Service.Start
import com.ergodicity.core.SessionsState

class SessionsManagerSpec extends TestKit(ActorSystem("SessionsManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(serviceManager: TestProbe, sessions: TestProbe) = TestActorRef(new {
    val ServiceManager = serviceManager.ref
    val Sessions = sessions.ref
  } with Engine with Connection with CreateListener with FutInfoReplication with OptInfoReplication with Sessions {

    val underlyingConnection = mock(classOf[CGConnection])

    val Connection = system.deadLetters

    val FutInfoStream = system.deadLetters

    val OptInfoStream = system.deadLetters

    val optInfoReplication = mock(classOf[Replication])

    val futInfoReplication = mock(classOf[Replication])

    def listener(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  })

  "Sessions Manager" must {
    "stash messages before ConnectionService is activated" in {
      val serviceManager = TestProbe()
      val sessions = TestProbe()

      val engine = mockEngine(serviceManager, sessions).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new SessionsManager(engine)).withDispatcher("deque-dispatcher"), "SessionsManager")

      when("got Start message before connection service started")
      manager ! Start
      then("should stash it")
      sessions.expectNoMsg(300.millis)

      when("Connection Service started")
      manager ! ServiceStarted(ConnectionService)

      then("should track Sessions state")
      sessions.expectMsg(SubscribeTransitionCallBack(manager))

      when("Sessions goes online")
      manager ! Transition(sessions.ref, SessionsState.LoadingOptionsContents, SessionsState.Online)

      then("Service Manager should be notified")
      serviceManager.expectMsg(ServiceStarted(SessionsService))
    }

    "stop actor on Service.Stop message" in {
      val serviceManager = TestProbe()
      val sessions = TestProbe()

      val engine = mockEngine(serviceManager, sessions).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new SessionsManager(engine)).withDispatcher("deque-dispatcher"), "SessionsManager")

      manager ! ServiceStarted(ConnectionService)
      watch(manager)

      when("stop Service")
      manager ! Service.Stop

      when("service manager should be notified")
      serviceManager.expectMsg(ServiceStopped(SessionsService))

      and("service manager actor terminated")
      expectMsg(Terminated(manager))
    }
  }
}