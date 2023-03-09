package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.LogMarkers.Companion.HOSTNAME
import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * NodeWrapper starts/stops node depending on whether node is reachable
 */
class NodeWrapper(
    private val config: NodeConfig,
    hostFactory: IHostFactory,
    private val registry: NodeRegistry,
    private val maxHealthCheckAttempts: Int = 6,
    private val nodeCheckInterval: Duration = Duration.ofSeconds(60)
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            HOSTNAME to config.host
        )
    )
    private val lock = ReentrantLock(true)
    @Volatile private var isStarted = false
    private var healthCheckPeriodicTask: Future<*>? = null
    val node: ISimulatorsNode = hostFactory.getHostFromConfig(config)

    var lastError: Exception? = null
    @Volatile var isEnabled: Boolean = true
        private set
    @Volatile private var isReachable: Boolean = false

    fun isAlive(): Boolean = isStarted && isReachable

    override fun toString(): String = "NodeWrapper for ${config.publicHost}"

    fun start(): Boolean {
        lock.withLock {
            if (isStarted) {
                logger.error(logMarker, "The node is already started. Node config: $config")
                return false
            }

            if (!node.isReachable()) {
                isReachable = false
                logger.error(logMarker, "Failed to start the node from config: $config. Reason: unreachable node: $node.")
                return false
            }
            isReachable = true

            logger.info(logMarker, "Starting the node from config: $config")
            try {
                node.prepareNode()
                logger.info(logMarker, "Successfully started the node from config: $config")
                isStarted = true
                lastError = null
            } catch (e: Exception) {
                logger.error(logMarker, "Failed to start the node from config: $config", e)
                lastError = e
            }
            return isStarted
        }
    }

    fun stop() {
        lock.withLock {
            if (!isStarted) {
                logger.error(logMarker, "The node is not started. Node config: $config")
                return
            }

            logger.info(logMarker, "Stopping the node from config: $config")
            stopPeriodicHealthCheck()
            node.dispose()
            isStarted = false
            logger.info(logMarker, "Successfully stopped the node from config: $config")
        }
    }

    fun reboot() {
        node.reboot()
    }

    fun disable() {
        isEnabled = false
        logger.info(logMarker, "Disabled $this")
    }

    fun enable() {
        isEnabled = true
        logger.info(logMarker, "Enabled $this")
    }

    fun startPeriodicHealthCheck() {
        if (!isStarted) {
            throw RuntimeException("Can not start polling stopped node. Call start() on node first")
        }

        val executor = Executors.newSingleThreadExecutor()
        var healthCheckAttempts = 0
        healthCheckPeriodicTask = executor.submit {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(nodeCheckInterval.toMillis())

                if (isStarted && node.isReachable()) {
                    healthCheckAttempts = 0
                    isReachable = true
                } else {
                    healthCheckAttempts++
                    logger.debug(logMarker, "Node $this is down for last $healthCheckAttempts tries")

                    if (healthCheckAttempts >= maxHealthCheckAttempts) {
                        isReachable = false
                        registry.removeIfPresent(this)
                        val message =
                            "Removing node [${node.remoteAddress}]: cannot reach the node for $maxHealthCheckAttempts tries"
                        logger.error(logMarker, message)
                        throw RuntimeException(message)
                    }
                }

            }
        }
        executor.shutdown()
    }

    private fun stopPeriodicHealthCheck() {
        healthCheckPeriodicTask?.cancel(true)
        healthCheckPeriodicTask = null
    }
}