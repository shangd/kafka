/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import org.apache.log4j._
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver}
import java.util
import com.google.protobuf.ByteString
import java.util.{Date, Properties}
import java.io.StringWriter
import scala.collection.JavaConversions._

object Scheduler extends org.apache.mesos.Scheduler {
  private val logger: Logger = Logger.getLogger(this.getClass)

  private val cluster: Cluster = new Cluster()
  cluster.load(clearTasks = true)

  private var driver: SchedulerDriver = null
  private val taskIds: util.List[String] = new util.concurrent.CopyOnWriteArrayList[String]()

  def getCluster: Cluster = cluster

  private def executor(broker: Broker): ExecutorInfo = {
    var cmd = "java -cp " + HttpServer.jarName
    cmd += " -Xmx" + broker.heap + "m"

    if (Config.debug) cmd += " -Ddebug"
    cmd += " ly.stealth.mesos.kafka.Executor"

    ExecutorInfo.newBuilder()
      .setExecutorId(ExecutorID.newBuilder.setValue(Broker.nextExecutorId(broker)))
      .setCommand(
        CommandInfo.newBuilder
          .addUris(CommandInfo.URI.newBuilder().setValue(Config.schedulerUrl + "/executor/" + HttpServer.jarName))
          .addUris(CommandInfo.URI.newBuilder().setValue(Config.schedulerUrl + "/kafka/" + HttpServer.kafkaDistName))
          .setValue(cmd)
      )
      .setName("BrokerExecutor")
      .build()
  }

  private def task(broker: Broker, offer: Offer): TaskInfo = {
    val port = findBrokerPort(offer)

    val props: Map[String, String] = Map(
      "broker.id" -> broker.id,
      "port" -> ("" + port),
      "zookeeper.connect" -> Config.kafkaZkConnect
    )

    val taskBuilder: TaskInfo.Builder = TaskInfo.newBuilder
      .setName("BrokerTask")
      .setTaskId(TaskID.newBuilder.setValue(Broker.nextTaskId(broker)).build)
      .setSlaveId(offer.getSlaveId)
      .setData(taskData(broker, props))
      .setExecutor(executor(broker))

    taskBuilder
      .addResources(Resource.newBuilder.setName("cpus").setType(Value.Type.SCALAR).setScalar(Value.Scalar.newBuilder.setValue(broker.cpus)))
      .addResources(Resource.newBuilder.setName("mem").setType(Value.Type.SCALAR).setScalar(Value.Scalar.newBuilder.setValue(broker.mem)))
      .addResources(Resource.newBuilder.setName("ports").setType(Value.Type.RANGES).setRanges(
      Value.Ranges.newBuilder.addRange(Value.Range.newBuilder().setBegin(port).setEnd(port)))
      )

    taskBuilder.build
  }

  def registered(driver: SchedulerDriver, id: FrameworkID, master: MasterInfo): Unit = {
    logger.info("[registered] framework:" + MesosStr.id(id.getValue) + " master:" + MesosStr.master(master))
    this.driver = driver
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo): Unit = {
    logger.info("[reregistered] master:" + MesosStr.master(master))
    this.driver = driver
  }

  def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]): Unit = {
    logger.info("[resourceOffers]\n" + MesosStr.offers(offers))
    syncClusterState(offers)
  }

  def offerRescinded(driver: SchedulerDriver, id: OfferID): Unit = {
    logger.info("[offerRescinded] " + MesosStr.id(id.getValue))
  }

  def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    logger.info("[statusUpdate] " + MesosStr.taskStatus(status))
    val broker = cluster.getBroker(Broker.idFromTaskId(status.getTaskId.getValue))

    status.getState match {
      case TaskState.TASK_RUNNING =>
        onBrokerStarted(broker, status)
      case TaskState.TASK_LOST | TaskState.TASK_FINISHED |
           TaskState.TASK_FAILED | TaskState.TASK_KILLED |
           TaskState.TASK_ERROR =>
        onBrokerStopped(broker, status)
      case _ => logger.warn("Got unexpected task state: " + status.getState)
    }

    syncClusterState()
  }

  def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]): Unit = {
    logger.info("[frameworkMessage] executor:" + MesosStr.id(executorId.getValue) + " slave:" + MesosStr.id(slaveId.getValue) + " data: " + new String(data))
  }

  def disconnected(driver: SchedulerDriver): Unit = {
    logger.info("[disconnected]")
    this.driver = null
  }

  def slaveLost(driver: SchedulerDriver, id: SlaveID): Unit = {
    logger.info("[slaveLost] " + MesosStr.id(id.getValue))
  }

  def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int): Unit = {
    logger.info("[executorLost] executor:" + MesosStr.id(executorId.getValue) + " slave:" + MesosStr.id(slaveId.getValue) + " status:" + status)
  }

  def error(driver: SchedulerDriver, message: String): Unit = {
    logger.info("[error] " + message)
  }

  private def onBrokerStarted(broker: Broker, status: TaskStatus): Unit = {
    if (broker == null) return

    if (broker.task != null) broker.task.running = true
    broker.failover.resetFailures()
  }

  private def onBrokerStopped(broker: Broker, status: TaskStatus): Unit = {
    taskIds.remove(status.getTaskId.getValue)
    if (broker == null) return

    broker.task = null
    val failed = status.getState != TaskState.TASK_FINISHED && status.getState != TaskState.TASK_KILLED

    if (failed) {
      broker.failover.registerFailure(new Date())

      var msg = "Broker " + broker.id + " failed to start " + broker.failover.failures
      if (broker.failover.maxTries != null) msg += "/" + broker.failover.maxTries

      if (!broker.failover.isMaxTriesExceeded) {
        msg += ", waiting " + broker.failover.currentDelay
        msg += ", next start ~ " + MesosStr.dateTime(broker.failover.delayExpires)
      } else {
        broker.active = false
        msg += ", failure limit exceeded"
        msg += ", deactivating broker"
      }

      logger.info(msg)
    }
  }

  def syncClusterState(offers: util.List[Offer] = new util.ArrayList[Offer]()): Unit = {
    logger.debug("[syncClusterState]")
    cluster.save()
    if (driver == null) return

    val now = new Date()
    for (offer <- offers) {
      var accepted = false

      for (broker <- cluster.getBrokers) {
        val acceptable = !accepted && broker.active &&
          broker.matches(offer) && !broker.failover.isWaitingDelay(now)

        if (broker.task == null && acceptable) {
          accepted = true
          launchTask(broker, offer)
        }
      }

      if (!accepted)
        driver.declineOffer(offer.getId)
    }

    for (id <- taskIds) {
      val broker = cluster.getBroker(Broker.idFromTaskId(id))
      if (broker == null || !broker.active) {
        logger.info("Killing task " + id)
        driver.killTask(TaskID.newBuilder.setValue(id).build)
      }
    }
  }

  def launchTask(broker: Broker, offer: Offer): Unit = {
    val task_ = task(broker, offer)
    val id = task_.getTaskId.getValue

    driver.launchTasks(util.Arrays.asList(offer.getId), util.Arrays.asList(task_))
    broker.task = new Broker.Task(id, offer.getHostname, findBrokerPort(offer))
    taskIds.add(id)

    logger.info("Launching task " + id + " by offer " + MesosStr.id(offer.getId.getValue) + "\n" + MesosStr.task(task_))
  }

  private def findBrokerPort(offer: Offer): Int = {
    for (resource <- offer.getResourcesList) {
      if (resource.getName == "ports") {
        val ranges: util.List[Value.Range] = resource.getRanges.getRangeList
        val range = if (ranges.isEmpty) null else ranges.get(0)

        if (range == null || !range.hasBegin) throw new IllegalStateException("Invalid port range in offer " + MesosStr.offer(offer))
        return range.getBegin.toInt
      }
    }

    throw new IllegalStateException("No port range in offer " + MesosStr.offer(offer))
  }

  private def taskData(broker: Broker, props: Map[String, String]): ByteString = {
    val p: Properties = new Properties()
    for ((k, v) <- broker.optionMap) p.setProperty(k, v)
    for ((k, v) <- props) p.setProperty(k, v)

    if (!p.containsKey("log.dirs"))
      p.setProperty("log.dirs", "kafka-logs")

    val buffer: StringWriter = new StringWriter()
    p.store(buffer, "")

    ByteString.copyFromUtf8("" + buffer)
  }

  def main(args: Array[String]) {
    initLogging()
    HttpServer.start()

    val frameworkBuilder = FrameworkInfo.newBuilder()
    frameworkBuilder.setUser(Config.mesosUser)
    frameworkBuilder.setName("Kafka Mesos")
    frameworkBuilder.setFailoverTimeout(Config.failoverTimeout)
    frameworkBuilder.setCheckpoint(true)

    val driver = new MesosSchedulerDriver(Scheduler, frameworkBuilder.build, Config.masterConnect)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() = {
        if (driver != null) driver.stop()
        HttpServer.stop()
      }
    })

    val status = if (driver.run eq Status.DRIVER_STOPPED) 0 else 1
    System.exit(status)
  }

  def initLogging() {
    BasicConfigurator.resetConfiguration()

    val root = Logger.getRootLogger
    root.setLevel(Level.INFO)

    Logger.getLogger("org.apache.zookeeper.ZooKeeper").setLevel(Level.WARN)

    val logger = Logger.getLogger(Scheduler.getClass)
    logger.setLevel(if (Config.debug) Level.DEBUG else Level.INFO)

    val layout = new PatternLayout("%d [%t] %-5p %c %x - %m%n")
    root.addAppender(new ConsoleAppender(layout))
  }
}