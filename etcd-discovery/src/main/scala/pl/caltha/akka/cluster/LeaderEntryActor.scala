package pl.caltha.akka.cluster

import scala.concurrent.duration.FiniteDuration

import akka.actor.FSM
import akka.actor.FSM.Failure
import akka.actor.Props
import akka.actor.Status
import akka.pattern.pipe
import pl.caltha.akka.etcd.EtcdClient
import pl.caltha.akka.etcd.EtcdError
import pl.caltha.akka.etcd.EtcdException
import pl.caltha.akka.etcd.EtcdResponse

/**
 * Actor responsible for periodical refresh of the /leader entry in etcd
 *
 * @param address: the address of cluster leader node.
 * @param etcdClient: EtcdClient to use.
 * @param settings: cluster discovery settings
 */
class LeaderEntryActor(
  address: String,
  etcdClient: EtcdClient,
  settings: ClusterDiscoverySettings)
    extends FSM[LeaderEntryActor.State, LeaderEntryActor.Data] {

  import LeaderEntryActor._

  implicit val executionContext = context.dispatcher

  val refreshInterval = settings.leaderEntryTTL / 2

  /**
   * Refresh the entry at leader path, assuming that it exists and the current value is our node's address.
   *
   * This method is used during the normal refresh cycle.
   */
  def refreshLeaderEntry() =
    etcdClient.compareAndSet(
      key = settings.leaderPath,
      value = address,
      ttl = Some(settings.leaderEntryTTL.toSeconds.asInstanceOf[Int]),
      prevValue = Some(address),
      prevExist = Some(true)).recover {
        case ex: EtcdException ⇒ ex.error
      }.pipeTo(self)

  /**
   * Create the leader entry, assuming it does not exist.
   *
   * This method is used when the leader entry has expired while the leader node was unable to reach etcd, or when
   * the leader entry was hijacked by another node. System operator will eventually shut down one of the contending
   * leaders, and if the current node prevails it will reclaim the leader entry after it expires.
   */
  def createLeaderEntry() =
    etcdClient.compareAndSet(
      key = settings.leaderPath,
      value = address,
      ttl = Some(settings.leaderEntryTTL.toSeconds.asInstanceOf[Int])).recover {
        case ex: EtcdException ⇒ ex.error
      }.pipeTo(self)

  when(Init){
    case Event(Initialize,_) =>
      createLeaderEntry()
      goto(AwaitingReply)
  }

  when(Idle) {
    case Event("refresh", _) ⇒
      refreshLeaderEntry()
      goto(AwaitingReply)
  }

  when(AwaitingReply) {
    case Event(EtcdResponse(_, _, _), _) ⇒
      goto(Idle)
    // the entry either expired or was hijacked by another node
    case Event(EtcdError(EtcdError.TestFailed | EtcdError.KeyNotFound, _, _, _), _) ⇒
      goto(Idle)
    // recoverable EtcdErrors
    case Event(err @ EtcdError(_, _, _, _),_) ⇒
      log.error(s"etcd error $err")
      goto(Idle).forMax(settings.etcdRetryDelay)
    // network errors
    case Event(Status.Failure(t),_) ⇒
      log.error(t, "etcd error")
      goto(Idle).forMax(settings.etcdRetryDelay)

  }

  whenUnhandled{
    case Event(Reset,_) =>
      goto(Init)
  }

  onTransition{
    case Init -> _ =>
      setTimer("refresh","refresh",refreshInterval,true)
    case _ -> Init =>
      cancelTimer("refresh")
  }

  startWith(Init,null)


  initialize()
}

object LeaderEntryActor {

  def props(address: String, etcdClient: EtcdClient, settings: ClusterDiscoverySettings) =
    Props(classOf[LeaderEntryActor], address, etcdClient, settings)

  trait State
  case object Init extends State
  case object Idle extends State
  case object AwaitingReply extends State

  case class Data(assumeEntryExists: Boolean)


  case object Initialize
  case object Reset
}
