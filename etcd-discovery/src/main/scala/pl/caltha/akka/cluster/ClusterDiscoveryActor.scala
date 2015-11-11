package pl.caltha.akka.cluster

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsSnapshot, MemberEvent, MemberExited, MemberRemoved, MemberUp, _}
import akka.pattern.pipe
import pl.caltha.akka.cluster.LeaderEntryActor.{Initialize, Reset}
import pl.caltha.akka.etcd.{EtcdClient, EtcdError, EtcdException, EtcdNode, EtcdResponse}

import scala.collection.immutable.Set
import scala.concurrent.Future
import scala.language.postfixOps

class ClusterDiscoveryActor(
                             etcdClient: EtcdClient,
                             cluster: Cluster,
                             settings: ClusterDiscoverySettings) extends LoggingFSM[ClusterDiscoveryActor.State, ClusterDiscoveryActor.Data] {

  import ClusterDiscoveryActor._

  private implicit val executor = context.system.dispatcher


  def etcd(operation: EtcdClient ⇒ Future[EtcdResponse]) =
    operation(etcdClient).recover {
      case ex: EtcdException ⇒ ex.error
    }.pipeTo(self)

  val seedList = context.actorOf(SeedListActor.props(etcdClient, settings), "seed-list")

  var isSeedNodes = true

  var _leaderEntry:ActorRef = context.system.deadLetters

  when(Initial) {
    case Event(Start, _) ⇒
      etcd(_.createDir(
        key = settings.etcdPath,
        ttl = None))
      stay()
    case Event(_: EtcdResponse, _) ⇒
      goto(Election)
    case Event(EtcdError(EtcdError.NodeExist, _, _, _), _) ⇒
      goto(Election)
    // I'd expect EtcdError.NodeExists, but that's what we get when /akka already exists
    case Event(EtcdError(EtcdError.NotFile, _, _, _), _) ⇒
      goto(Election)
  }

  def electionBid() =
    etcd(_.compareAndSet(
      key = settings.leaderPath,
      value = cluster.selfAddress.toString,
      ttl = Some(settings.leaderEntryTTL.toSeconds.asInstanceOf[Int]),
      prevValue = None,
      prevIndex = None,
      prevExist = Some(false)))

  onTransition {
    case (_, Election) ⇒
      log.info("starting election")
      electionBid()
  }

  when(Election) {
    case Event(_: EtcdResponse, _) ⇒
      goto(Leader)
    case Event(EtcdError(EtcdError.NodeExist, _, _, _), _) ⇒
      goto(Follower)
    case Event(err@EtcdError, _) ⇒
      log.error(s"Election error: $err")
      setTimer("retry", Retry, settings.etcdRetryDelay, false)
      stay()
    case Event(Status.Failure(t), _) ⇒
      log.error(t, "Election error")
      setTimer("retry", Retry, settings.etcdRetryDelay, false)
      stay()
    case Event(Retry, _) ⇒
      log.warning("retrying")
      electionBid()
      stay()
  }

  onTransition {
    case (_, Leader) ⇒
      // bootstrap the cluster
      log.info("assuming Leader role")
      cluster.join(cluster.selfAddress)
      cluster.subscribe(self, initialStateMode = InitialStateAsSnapshot, classOf[MemberEvent],classOf[LeaderChanged])
      if(_leaderEntry == context.system.deadLetters)
        _leaderEntry = context.actorOf(LeaderEntryActor.props(cluster.selfAddress.toString, etcdClient, settings),"leader-entry")
      else
        _leaderEntry ! Reset

      _leaderEntry ! Initialize
  }

  when(Leader) {
    case Event(CurrentClusterState(members, _, _, _, _), _) ⇒
      seedList ! SeedListActor.InitialState(members.map(_.address.toString))
      stay().using(members.map(_.address))
    case Event(MemberUp(member), seeds) ⇒
      log.info("member up {}",member)
      if(seeds.size < settings.numOfSeeds){
        seedList ! SeedListActor.MemberAdded(member.address.toString)
        stay().using(seeds + member.address)
      }else
        stay()
    case Event(MemberExited(member), seeds) ⇒
      log.info("member exist {}",member)
      seedList ! SeedListActor.MemberRemoved(member.address.toString)
      stay().using(seeds - member.address)
    case Event(MemberRemoved(member, _), seeds) ⇒
      log.info("member removed {}",member)
      if (seeds.contains(member.address))
        seedList ! SeedListActor.MemberRemoved(member.address.toString)
      stay().using(seeds - member.address)
    case Event(LeaderChanged(optAddress),_) =>
      optAddress match {
        case Some(addr) if addr != cluster.selfAddress =>
          _leaderEntry ! Reset
          goto(Follower)
        case _ =>
          stay()
      }
  }

  def fetchSeeds() =
    etcd(_.get(
      key = settings.seedsPath,
      recursive = true,
      sorted = false))

  onTransition {
    case Leader -> Follower =>
      log.info("demote to normal seed")
    case (_, Follower) ⇒
      log.info("assuming Follower role")
      setTimer("seedsFetch", SeedsFetchTimeout, settings.seedsFetchTimeout, repeat = false)
      cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent],classOf[UnreachableMember],classOf[LeaderChanged])
      fetchSeeds()
  }

  when(Follower) {
    case Event(EtcdResponse("get", EtcdNode(_, _, _, _, _, Some(true), Some(nodes)), _), _) ⇒
      nodes.foreach(node => {
        log.info("seed node: {} , index:{}", node.value, node.key)
      })
      val seeds = nodes.flatMap(_.value).map(AddressFromURIString(_))
      cluster.joinSeedNodes(seeds)
      cancelTimer("seedsFetch")
      setTimer("seedsJoin", JoinTimeout, settings.seedsJoinTimeout, repeat = false)
      log.info("set timer seedsJoin")
      Thread.sleep(1000)
      stay()
    case Event(EtcdError(EtcdError.KeyNotFound, _, _, _), _) ⇒
      setTimer("retry", Retry, settings.etcdRetryDelay, repeat = false)
      stay()
    case Event(Retry, _) ⇒
      fetchSeeds()
      stay()
    case Event(SeedsFetchTimeout, _) ⇒
      log.info(s"failed to fetch seed node information in ${settings.seedsFetchTimeout.toMillis} ms")
      goto(Election)
    case Event(JoinTimeout, _) ⇒
      log.info(s"seed nodes failed to respond in ${settings.seedsJoinTimeout.toMillis} ms")
      goto(Election)
    case Event(LeaderChanged(Some(address)), _) if address == cluster.selfAddress ⇒
      log.info("promot to leader")
      goto(Leader)
    case Event(LeaderChanged(optAddress), _) ⇒
      log.info(s"seen leader change to $optAddress")
      stay()
    case Event(x:MemberUp,_) =>
      if(x.member.address == cluster.selfAddress){
        cancelTimer("seedsFetch")
        cancelTimer("seedsJoin")

        log.info("join cluster succeed")
      }
      stay()
    case Event(x@CurrentClusterState(members, unreachable, seenBy, leader, roleLeaderMap), _) ⇒
      log.info("CurrentClusterState members:{}, leader:{}",members,leader)
//      log.info("joined the cluster ,{}", x)
      //      log.info("members:{}, leader:{}",members,leader)
//      cancelTimer("seedsFetch")
//      cancelTimer("seedsJoin")
      stay()
    case Event(_: ClusterDomainEvent, _) ⇒
      stay()
  }

  whenUnhandled {
    case Event("shutdown", _) =>
      val f1 = etcdClient.compareAndDelete(settings.leaderPath, Some(cluster.selfAddress.toString))
      seedList ! SeedListActor.Shutdown(cluster.selfAddress.toString)
      stop()
    case Event(msg, _) ⇒
      log.warning(s"unhandled message $msg")
      stay()
  }

  startWith(Initial, Set.empty)
  initialize()
}

object ClusterDiscoveryActor {

  def props(etcd: EtcdClient, cluster: Cluster, settings: ClusterDiscoverySettings) =
    Props(classOf[ClusterDiscoveryActor], etcd, cluster, settings)

  /*
   * FSM States
   */
  sealed trait State

  case object Initial extends State

  case object Election extends State

  case object Leader extends State

  case object Follower extends State

  /**
    * FSM Data = known cluster nodes
    */
  type Data = Set[Address]

  /**
    * Message that triggers actor's initialization
    */
  case object Start

  case object Retry

  private case object SeedsFetchTimeout

  private case object JoinTimeout

}
