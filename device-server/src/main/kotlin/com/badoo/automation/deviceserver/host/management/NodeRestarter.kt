package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.ios.SessionEntry
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class NodeRestarter(
    private val nodeRegistry: NodeRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val activeSessionsCheckInterval = Duration.ofSeconds(30)

    fun restartNodeWrappers(
        nodes: Set<NodeWrapper>,
        isParallel: Boolean,
        shouldReboot: Boolean,
        forceReboot: Boolean
    ) {
        if (isParallel) {
            logger.info("Going to restart nodes in parallel.")

            if (nodes.isEmpty()) {
                logger.debug("Unable to restart empty list of nodes")
                return
            }

            val executor = Executors.newFixedThreadPool(nodes.size)
            val tasks = mutableListOf<Future<*>>()
            nodes.forEach { nodeWrapper ->
                val task: Future<*> = executor.submit {
                    try {
                        rebootSimulatorHost(nodeWrapper, forceReboot, shouldReboot)
                    } catch (t: Throwable) {
                        logger.error("Failed to reboot simulator host ${nodeWrapper.node.publicHostName} due to issue. ${t.javaClass.name}, ${t.message}", t)
                    }
                }
                tasks.add(task)
            }
            executor.shutdown()

            tasks.forEach { it.get() }

            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (e: InterruptedException) {
                println("Failed to awaitTermination while reboot all simulator hosts due to issue. ${e.javaClass.name}, ${e.message}")
            }
        } else {
            logger.info("Going to restart nodes sequentially.")
            nodes.forEach { nodeWrapper ->
                try {
                    rebootSimulatorHost(nodeWrapper, forceReboot, shouldReboot)
                } catch (t: Throwable) {
                    logger.error("Failed to reboot simulator host ${nodeWrapper.node.publicHostName} due to issue. ${t.javaClass.name}, ${t.message}", t)
                }
            }
        }

    }

    private fun rebootSimulatorHost(nodeWrapper: NodeWrapper, forceReboot: Boolean, shouldReboot: Boolean) {
        val startTime = System.nanoTime()
        logger.info("Going to restart simulator host ${nodeWrapper.node.publicHostName}")
        nodeWrapper.disable()

        if (forceReboot) {
            clearActiveSessions(nodeWrapper.node)
        } else if (activeSessions(nodeWrapper.node).isNotEmpty()) {
            logger.error("Failed to re-start node $nodeWrapper as it has active sessions with infinite timeout")
            nodeWrapper.enable()
            return
        }

        nodeWrapper.stop()

        if (shouldReboot) {
            nodeWrapper.reboot()
        }

        if (nodeWrapper.start()) {
            nodeWrapper.startPeriodicHealthCheck()
            nodeWrapper.enable()
        } else {
            logger.error("Failed to re-start node $nodeWrapper")
            nodeRegistry.removeIfPresent(nodeWrapper)
        }

        val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime)
        logger.info("Successfully restarted simulator host ${nodeWrapper.node.publicHostName}. Took time ${elapsedSeconds} seconds")
    }

    private fun activeSessions(node: ISimulatorsNode): Collection<SessionEntry> {
        return nodeRegistry.activeDevices.activeDevicesByNode(node.publicHostName).values
    }

    private fun clearActiveSessions(node: ISimulatorsNode) {
        val sessions = activeSessions(node).map { it.ref }
        return nodeRegistry.activeDevices.releaseDevices(sessions.toList(), "Reboot of all simulator hosts")
    }
}
