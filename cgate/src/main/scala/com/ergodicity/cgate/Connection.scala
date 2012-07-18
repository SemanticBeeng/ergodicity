package com.ergodicity.cgate

import akka.util.duration._
import akka.actor.FSM.Failure
import ru.micexrts.cgate.{Connection => CGConnection}
import akka.actor.{Cancellable, FSM, Actor}

object Connection {

  val StateUpdateTimeOut = 1.second

  sealed trait ConnectionProtocol {
    def prefix: String
  }

  case object Tcp extends ConnectionProtocol {
    def prefix = "p2tcp://"
  }

  case object Lrpcq extends ConnectionProtocol {
    def prefix = "p2lrpcq://"
  }

  case class Open(protocol: ConnectionProtocol, host: String, port: Int, appName: String) {
    val connectionString = protocol.prefix + host + ":" + port + ";app_name=" + appName
  }

  case object Close

  case class StartMessageProcessing(timeout: Int)

  def apply(underlying: CGConnection) = new Connection(underlying)
}

protected[cgate] case class ConnectionState(state: State)

class Connection(protected[cgate] val underlying: CGConnection) extends Actor with FSM[State, Option[Cancellable]] {

  import Connection._

  startWith(Closed, None)

  when(Closed) {
    case Event(properties@Open(protocol, host, port, appName), _) =>
      log.info("Open; Host = " + properties.host + "; Port = " + properties.port + "; AppName = " + properties.appName + "; Protocol = " + properties.protocol)
      underlying.open(properties.connectionString)
      goto(Opening)
  }

  when(Opening, stateTimeout = 3.second) {
    case Event(FSM.StateTimeout, _) => stop(Failure("Connecting timeout"))
  }

  when(Active) {
    case Event(Close, cancellable) =>
      log.info("Close connection")
      cancellable.foreach(_.cancel())
      underlying.close()
      stop(Failure("Connection closed"))

    case Event(ConnectionState(state@(Closed | Error | Opening)), _) => stop(Failure("Connection switched to failed state = " + state))

    case Event(StartMessageProcessing(timeout), None) =>
      val cancellable = context.system.scheduler.schedule(0 millisecond, 0 millisecond) {
        underlying.process(timeout)
      }
      stay() using Some(cancellable)
  }

  onTransition {
    case Closed -> Opening => log.info("Trying to establish connection to CGate router")
    case _ -> Active => log.info("Successfully connected to CGate router");
  }

  whenUnhandled {
    case Event(ConnectionState(state), _) if (state != stateName) =>
      log.debug("Connection state changed to " + state)
      goto(state)
  }

  onTermination {
    case StopEvent(reason, s, d) =>
      log.error("Connection failed, reason = " + reason)
      d foreach {
        _.cancel()
      }
  }

  initialize

  // Subscribe for connection state updates
  context.system.scheduler.schedule(0 milliseconds, StateUpdateTimeOut) {
    self ! ConnectionState(underlying.getState)
  }
}