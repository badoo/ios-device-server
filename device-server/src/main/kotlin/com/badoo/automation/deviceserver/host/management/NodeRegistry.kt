package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceDTO
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.host.management.errors.NoAliveNodesException
import com.badoo.automation.deviceserver.host.management.errors.NoNodesRegisteredException
import com.badoo.automation.deviceserver.ios.ActiveDevices
import com.badoo.automation.deviceserver.ios.simulator.simulatorsThreadPool
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors.toSet
import java.util.stream.Stream

class NodeRegistry(val activeDevices: ActiveDevices = ActiveDevices()) {
    private var initialRegistrationComplete: Boolean = false
    private val nodeWrappers = ConcurrentHashMap.newKeySet<NodeWrapper>()
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    fun getInitialRegistrationComplete(): Boolean = initialRegistrationComplete

    fun setInitialRegistrationComplete() {
        initialRegistrationComplete = true
    }

    fun add(wrapper: NodeWrapper) {
        if (nodeWrappers.contains(wrapper)) {
            logger.warn("${wrapper.node.remoteAddress} is already registered")
        } else {
            nodeWrappers.add(wrapper)
            logger.info("Registered node ${wrapper.node.remoteAddress}")
        }
    }

    fun removeIfPresent(wrapper: NodeWrapper) {
        nodeWrappers.remove(wrapper)
        logger.info("Unregistered node ${wrapper.node.remoteAddress}")
        activeDevices.unregisterNodeDevices(wrapper.node)
    }

    fun getAll(): Set<NodeWrapper> {
        return nodeWrappers
    }

    private fun getAlive(): Set<NodeWrapper> {
        val filteredStream: Stream<NodeWrapper> = nodeWrappers
            .parallelStream()
            .filter { it.isAlive() }
        val aliveNodes = filteredStream.collect(toSet())
        return aliveNodes
    }

    fun capacitiesTotal(desiredCapabilities: DesiredCapabilities): Map<String, Int> {
        val capacities: Stream<Int> = getAlive()
                .parallelStream()
                .map { it.node.totalCapacity(desiredCapabilities) }

        val count = capacities.reduce(0, Integer::sum)

        return mapOf("total" to count)
    }

    fun createDeviceAsync(desiredCapabilities: DesiredCapabilities, deviceTimeout: Duration, userId: String?): DeviceDTO {
        if (getAll().isEmpty()) {
            throw NoNodesRegisteredException("No nodes are registered to create a device")
        }

        // FIXME: sequential
        val node: ISimulatorsNode = getAlive() // FIXME: we get stuck somewhere here
                .map { wrapper -> wrapper.node }
                .maxBy { node -> node.capacityRemaining(desiredCapabilities) }
                ?: throw NoAliveNodesException("No alive nodes are available to create device at the moment")

        // FIXME: we get stuck somewhere here
        val dto = node.createDeviceAsync(desiredCapabilities)

        val logMarker: Marker = MapEntriesAppendingMarker(mutableMapOf(
                LogMarkers.DEVICE_REF to dto.ref,
                LogMarkers.UDID to dto.info.udid
        ))

        logger.info(logMarker, "Create device started, register with timeout ${deviceTimeout.seconds} secs")

        activeDevices.registerDevice(dto.ref, node, deviceTimeout, userId)

        return dto
    }

    fun dispose() {
        //FIXME: Do proper clean up on server exit
        val list: List<Job> = nodeWrappers.map { launch(simulatorsThreadPool) { it.stop() } }
        runBlocking { list.forEach { it.join() } }
        nodeWrappers.clear()
    }
}