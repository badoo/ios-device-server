package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.util.*

class PortAllocator(
    private val remote: IRemote,
    min: Int = PORT_RANGE_START,
    max: Int = PORT_RANGE_END,
    private val portsPerAllocation: Int = DeviceAllocatedPorts.portsCount()
) {
    companion object {
        // this port range seems to be free and not in conflict with zabbix, etc.
        const val PORT_RANGE_START = 41800
        const val PORT_RANGE_END = 65500
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private val availablePorts = Collections.synchronizedSet(LinkedHashSet<Int>()).apply {
        addAll(IntRange(min, max))
    }

    fun allocateDAP(): DeviceAllocatedPorts {
        val ports = allocate(portsPerAllocation)
        val allocatedPorts = DeviceAllocatedPorts.fromList(ports.toList())
        logger.info(logMarker, "Allocated ports: $allocatedPorts")
        return allocatedPorts
    }

    fun deallocateDAP(unusedPorts: DeviceAllocatedPorts) {
        availablePorts.addAll(unusedPorts.toList())
        logger.info(logMarker, "Deallocated ports: $unusedPorts")
    }

    private fun allocate(entries: Int): Set<Int> {
        val allocatedPorts = mutableSetOf<Int>()

        synchronized(availablePorts) {
            val occupiedPorts = getOccupiedPortsWithRetry()
            availablePorts.removeAll(occupiedPorts)

            if (availablePorts.size < entries) {
                throw RuntimeException("No more ports to allocate")
            }
            val mutableIterator: MutableIterator<Int> = availablePorts.iterator() // Must be in the synchronized block
            repeat(entries) {
                if (mutableIterator.hasNext()) {
                    val port: Int = mutableIterator.next()
                    mutableIterator.remove()
                    allocatedPorts.add(port)
                } else {
                    throw RuntimeException("No more ports to allocate")
                }
            }
        }

        return allocatedPorts
    }

    fun refreshPortAvailability() {
        val occupiedPorts = getOccupiedPortsWithRetry()
        availablePorts.removeAll(occupiedPorts)
    }

    private fun getOccupiedPorts(): Set<Int> {
        val result = remote.exec(listOf("/usr/sbin/netstat", "-anv"), mapOf(), returnFailure = true, timeOutSeconds = 120)

        if (result.isSuccess) {
            val spaceRegex = "\\s+".toRegex()

            val netstatOccupiedPorts = result.stdOut.lines()
                .filter { it.contains("LISTEN") && it.trim().startsWith("tcp4") }

            val occupiedPorts = netstatOccupiedPorts
                .mapNotNull { line ->
                    val parts = line.split(spaceRegex)
                    parts.getOrNull(3)?.split(".")?.getOrNull(1)?.toIntOrNull()
                }
                .toSet()

            logger.info(logMarker, "Received occupied ports: ${occupiedPorts.toList().sorted().joinToString(",")}. Output from netstat:\n${netstatOccupiedPorts.joinToString("\n")}")

            return occupiedPorts
        } else {
            throw IllegalStateException("Failed to get occupied ports: ${result.stdErr}")
        }
    }

    fun getOccupiedPortsWithRetry(): Set<Int> {
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                return getOccupiedPorts()
            } catch (e: Exception) {
                logger.error(logMarker, "Attempt ${attempt + 1} to get occupied ports failed: ${e.message}", e)
                lastException = e
            }
        }
        val errorMessage = "Failed to get occupied ports for host ${remote.publicHostName}: ${lastException?.message}"
        logger.error(logMarker, errorMessage)
        throw IllegalStateException(errorMessage, lastException)
    }
}