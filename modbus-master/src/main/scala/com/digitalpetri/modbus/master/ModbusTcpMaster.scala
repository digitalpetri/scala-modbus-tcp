package com.digitalpetri.modbus.master

import com.codahale.metrics.{Timer, MetricRegistry}
import com.digitalpetri.modbus.layers.TcpPayload
import com.digitalpetri.modbus.master.ModbusTcpMaster.ModbusTcpMasterConfig
import com.digitalpetri.modbus.{Modbus, ModbusResponse, ModbusRequest}
import io.netty.channel._
import io.netty.util.{Timeout, TimerTask, HashedWheelTimer}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import org.slf4j.LoggerFactory
import scala.Some
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}


class ModbusTcpMaster(config: ModbusTcpMasterConfig) extends TcpServiceResponseHandler {

  implicit val executionContext = config.executionContext

  val logger = config.instanceId match {
    case Some(instanceId) => LoggerFactory.getLogger(s"${getClass.getName}.$instanceId")
    case None => LoggerFactory.getLogger(getClass)
  }

  val requestCount      = config.metricRegistry.counter(metricName("request-count"))
  val responseCount     = config.metricRegistry.counter(metricName("response-count"))
  val lateResponseCount = config.metricRegistry.counter(metricName("late-response-count"))
  val timeoutCount      = config.metricRegistry.counter(metricName("timeout-count"))
  val responseTime      = config.metricRegistry.timer(metricName("response-time"))

  val promises        = new ConcurrentHashMap[Short, (Promise[ModbusResponse], Timeout, Timer.Context)]()
  val channelManager  = new ModbusChannelManager(this, config)
  val transactionId   = new AtomicInteger(0)

  def sendRequest(request: ModbusRequest, unitId: Short = 0): Future[ModbusResponse] = {
    val promise = Promise[ModbusResponse]()

    channelManager.getChannel match {
      case Left(fch) => fch.onComplete {
        case Success(ch) => writeToChannel(ch, promise, request, unitId)
        case Failure(ex) => promise.failure(ex)
      }
      case Right(ch) => writeToChannel(ch, promise, request, unitId)
    }

    promise.future
  }

  def disconnect(): Unit = {
    channelManager.disconnect()
    promises.clear()
  }

  /** Writes a request to the channel and flushes it. */
  private def writeToChannel(channel: Channel,
                             promise: Promise[ModbusResponse],
                             request: ModbusRequest,
                             unitId: Short): Unit = {

    val txId = transactionId.getAndIncrement.toShort

    val timeout = config.wheelTimer.newTimeout(
      new TimeoutTask(txId),
      config.timeout.toMillis,
      TimeUnit.MILLISECONDS)

    promises.put(txId, (promise, timeout, responseTime.time()))

    channel.writeAndFlush(TcpPayload(txId, unitId, request))
    requestCount.inc()
  }

  def onServiceResponse(service: TcpServiceResponse): Unit = {
    promises.remove(service.transactionId) match {
      case (p,t,c) =>
        responseCount.inc()
        c.stop()
        t.cancel()
        p.success(service.response)

      case null =>
        lateResponseCount.inc()
        logger.debug(s"Received response for unknown transactionId: $service")
    }
  }

  private def metricName(name: String) =
    MetricRegistry.name(classOf[ModbusTcpMaster], config.instanceId.getOrElse(""), name)

  private class TimeoutTask(txId: Short) extends TimerTask {
    def run(timeout: Timeout): Unit = {
      promises.remove(txId) match {
        case (p,t,c) =>
          timeoutCount.inc()
          p.failure(new Exception(s"request timed out after ${config.timeout.toMillis}ms"))

        case null => // Just made it...
      }
    }
  }

}

object ModbusTcpMaster {

  case class ModbusTcpMasterConfig(host: String,
                                   port: Int = 502,
                                   timeout: Duration = 5.seconds,
                                   executionContext: ExecutionContext = ExecutionContext.global,
                                   eventLoop: EventLoopGroup = Modbus.SharedEventLoop,
                                   wheelTimer: HashedWheelTimer = Modbus.SharedWheelTimer,
                                   metricRegistry: MetricRegistry = Modbus.SharedMetricRegistry,
                                   instanceId: Option[String] = None)


}