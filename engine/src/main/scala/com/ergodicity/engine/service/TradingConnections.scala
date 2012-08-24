package com.ergodicity.engine.service

import com.ergodicity.engine.{Services, Engine}
import akka.actor._
import akka.util.duration._
import com.ergodicity.cgate.{Connection => ErgodicityConnection, Active, State}
import akka.actor.FSM.{Transition, CurrentState, SubscribeTransitionCallBack}
import com.ergodicity.engine.service.TradingConnectionsManager.{ManagerData, ManagerState}
import scalaz._
import Scalaz._
import com.ergodicity.engine.underlying.UnderlyingTradingConnections

case object TradingConnectionsServiceId extends ServiceId

trait TradingConnections {
  this: Services =>

  def engine: Engine with UnderlyingTradingConnections

  private[this] val connectionManager = context.actorOf(Props(new TradingConnectionsManager(this, engine)), "TradingConnectionsManager")

  register(TradingConnectionsServiceId, connectionManager)
}

object TradingConnectionsManager {

  sealed trait ManagerState

  case object Idle extends ManagerState

  case object Starting extends ManagerState

  case object Connected extends ManagerState

  case object Stopping extends ManagerState


  sealed trait ManagerData

  case object Blank extends ManagerData

  case class ConnectionsStates(publisher: Option[State] = None, replies: Option[State] = None) extends ManagerData

}

protected[service] class TradingConnectionsManager(services: Services, engine: Engine with UnderlyingTradingConnections) extends Actor with LoggingFSM[ManagerState, ManagerData] {

  import engine._
  import services._
  import TradingConnectionsManager._

  implicit val Id = TradingConnectionsServiceId

  val PublisherConnection = context.actorOf(Props(new ErgodicityConnection(underlyingPublisherConnection)), "PublisherConnection")
  val RepliesConnection = context.actorOf(Props(new ErgodicityConnection(underlyingRepliesConnection)), "RepliesConnection")

  context.watch(PublisherConnection)
  context.watch(RepliesConnection)

  startWith(Idle, Blank)

  when(Idle) {
    case Event(Service.Start, Blank) =>
      // Subscribe for connection states
      PublisherConnection ! SubscribeTransitionCallBack(self)
      RepliesConnection ! SubscribeTransitionCallBack(self)

      // Open connections
      PublisherConnection ! ErgodicityConnection.Open
      RepliesConnection ! ErgodicityConnection.Open

      goto(Starting) using ConnectionsStates()
  }

  when(Starting) {
    case Event(CurrentState(PublisherConnection, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(publisher = Some(state)))

    case Event(CurrentState(RepliesConnection, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(replies = Some(state)))

    case Event(Transition(PublisherConnection, _, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(publisher = Some(state)))

    case Event(Transition(RepliesConnection, _, state: com.ergodicity.cgate.State), states@ConnectionsStates(_, _)) =>
      handleConnectionsStates(states.copy(replies = Some(state)))
  }

  when(Connected) {
    case Event(Service.Stop, _) =>
      PublisherConnection ! ErgodicityConnection.Close
      PublisherConnection ! ErgodicityConnection.Dispose
      RepliesConnection ! ErgodicityConnection.Close
      RepliesConnection ! ErgodicityConnection.Dispose
      goto(Stopping)
  }

  when(Stopping, stateTimeout = 1.second) {
    case Event(Terminated(conn), _) =>
      log.info("Connection terminated: " + conn)
      stay()

    case Event(FSM.StateTimeout, _) =>
      serviceStopped
      stop(FSM.Shutdown)
  }

  whenUnhandled {
    case Event(Terminated(PublisherConnection | RepliesConnection), _) =>
      serviceFailed("Trading connection unexpected terminated")

    case Event(CurrentState(PublisherConnection | RepliesConnection, com.ergodicity.cgate.Error), _) =>
      serviceFailed("Trading connection switched to Error state")

    case Event(Transition(PublisherConnection | RepliesConnection, _, com.ergodicity.cgate.Error), _) =>
      serviceFailed("Trading connection switched to Error state")
  }

  private def handleConnectionsStates(states: ConnectionsStates) = (states.publisher <**> states.replies)((_, _)) match {
    case Some((Active, Active)) => goto(Connected) using Blank
    case _ => stay() using states
  }

  onTransition {
    case Starting -> Connected => serviceStarted
  }
}