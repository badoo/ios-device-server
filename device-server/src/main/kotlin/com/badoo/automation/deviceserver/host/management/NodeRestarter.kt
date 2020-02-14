package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.ios.SessionEntry
import org.slf4j.LoggerFactory

class NodeRestarter(
    private val nodeRegistry: NodeRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    fun restartNodeWrappers(
        nodes: Set<NodeWrapper>,
        isParallel: Boolean
    ) {
        val nodesToRestart = if (isParallel) {
            logger.info("Going to restart nodes in parallel.")
            nodes.parallelStream()
        } else {
            logger.info("Going to restart nodes sequentially.")
            nodes.stream()
        }

        nodesToRestart.forEach { nodeWrapper ->
            if (activeSessions(nodeWrapper.node).isNotEmpty()) {
                logger.error("Failed to re-start node $nodeWrapper as it has active sessions with infinite timeout")
                return@forEach
            }

            nodeWrapper.disable()
            nodeWrapper.stop()

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
