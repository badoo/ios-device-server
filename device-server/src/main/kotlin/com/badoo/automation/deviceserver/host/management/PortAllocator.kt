package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.util.concurrent.ConcurrentHashMap

class PortAllocator(private val remote: IRemote, min: Int = PORT_RANGE_START, max: Int = PORT_RANGE_END) {
    companion object {
        // this port range seems to be free and not in conflict with zabbix, etc.
        const val PORT_RANGE_START = 41798
        const val PORT_RANGE_END = 62507
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(mapOf(
        LogMarkers.HOSTNAME to remote.hostName
    ))

    private val ports = ConcurrentHashMap.newKeySet<Int>()

    init {
        // Initialize the set with all ports in the range
        ports.addAll(IntRange(min, max).toSet())
    }

    fun allocateDAP(): DeviceAllocatedPorts {
        val take = allocate(5)
        return DeviceAllocatedPorts(take[0], take[1], take[2], take[3], take[4])
    }

    fun deallocateDAP(allocatedPorts: DeviceAllocatedPorts) {
        ports.addAll(allocatedPorts.toSet())
    }

    fun available(): Int {
        return ports.size
    }

    private fun allocate(entries: Int): List<Int> {
        synchronized(this) {
            if (ports.size < entries) {
                throw RuntimeException("No more ports to allocate")
            }
            val takenPorts = ports.take(entries)
            ports.removeAll(takenPorts)
            return takenPorts
        }
    }

    fun refreshPortAvailability() {
        val occupiedPorts = getOccupiedPortsWithRetry()
        occupiedPorts.forEach { port ->
            ports.remove(port)
        }
    }

    private fun getOccupiedPorts(): Set<Int> {
        val result = remote.shell("/usr/sbin/netstat -anv")

        if (result.isSuccess) {
            val spaceRegex = "\\s+".toRegex()

            val occupiedPorts = result.stdOut.lines()
                .filter { it.contains("LISTEN") && it.trim().startsWith("tcp4") }
                .mapNotNull { line ->
                    val parts = line.split(spaceRegex)
                    parts.getOrNull(3)?.split(".")?.getOrNull(1)?.toIntOrNull()
                }
                .toSet()

            logger.info(logMarker, "Received occupied ports: ${occupiedPorts.joinToString(", ")}")

            return occupiedPorts
        } else {
            throw IllegalStateException("Failed to get occupied ports: ${result.stdErr}")
        }
    }

    fun getOccupiedPortsWithRetry(): Set<Int> {
        1.rangeTo(3).forEach { attempt ->
            try {
                return getOccupiedPorts()
            } catch (e: IllegalStateException) {
                logger.warn(logMarker, "Attempt $attempt to get occupied ports failed: ${e.message}")
                if (attempt == 3) {
                    logger.error(logMarker, "Failed to get occupied ports for host ${remote.publicHostName}: ${e.message}")
                    throw e
                }
            }
        }
        throw IllegalStateException("Failed to get occupied ports after 3 attempts")
    }
}