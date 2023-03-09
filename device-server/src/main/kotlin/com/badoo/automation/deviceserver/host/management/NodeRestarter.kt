package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.ios.SessionEntry
import org.slf4j.LoggerFactory
import java.time.Duration

class NodeRestarter(
    private val nodeRegistry: NodeRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val activeSessionsCheckInterval = Duration.ofSeconds(30)

    fun restartNodeWrappers(
        nodes: Set<NodeWrapper>,
        isParallel: Boolean,
        shouldReboot: Boolean
    ) {
        val nodesToRestart = if (isParallel) {
            logger.info("Going to restart nodes in parallel.")
            nodes.parallelStream()
        } else {
            logger.info("Going to restart nodes sequentially.")
            nodes.stream()
        }

        nodesToRestart.forEach { nodeWrapper ->
            nodeWrapper.disable()

            if (activeSessions(nodeWrapper.node).isNotEmpty()) {
                logger.error("Failed to re-start node $nodeWrapper as it has active sessions with infinite timeout")
                nodeWrapper.enable()
                return@forEach
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
        }
    }

    private fun activeSessions(node: ISimulatorsNode): Collection<SessionEntry> {
        return nodeRegistry.activeDevices.activeDevicesByNode(node.publicHostName).values
    }
}
