package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import com.badoo.automation.deviceserver.host.IRemote
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PortAllocator(private val remote: IRemote, min: Int = PORT_RANGE_START, max: Int = PORT_RANGE_END) {
    companion object {
        // this port range seems to be free and not in conflict with zabbix, etc.
        const val PORT_RANGE_START = 41798
        const val PORT_RANGE_END = 62507
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val ports = ConcurrentHashMap.newKeySet<Int>()
    private val lock = ReentrantLock(true)

    init {
        // Initialize the set with all ports in the range
        ports.addAll(IntRange(min, max).toSet())
        logger.info("PortAllocator initialized with ports from $min to $max")
    }


    fun allocateDAP(): DeviceAllocatedPorts {
        val take = allocate(5)
        return DeviceAllocatedPorts(take[0], take[1], take[2], take[3], take[4])
    }

    fun deallocateDAP(allocatedPorts: DeviceAllocatedPorts) {
        lock.withLock {
            allocatedPorts.toSet().forEach { port ->
                ports.add(port)
            }
        }
    }

    fun available(): Int {
        return ports.size
    }

    fun getOccupiedPorts(): Set<Int> {
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
                logger.warn("Attempt $attempt to get occupied ports failed: ${e.message}")
                if (attempt == 3) {
                    throw e
                }
            }
        }
        throw IllegalStateException("Failed to get occupied ports after 3 attempts")
    }

    private fun allocate(entries: Int): List<Int> {
        val occupiedPorts = getOccupiedPortsWithRetry()
        lock.withLock {
            occupiedPorts.forEach { port ->
                ports.remove(port)
            }

            if (ports.size < entries) {
                throw RuntimeException("No more ports to allocate")
            }

            val takenPorts = ports.take(entries)
            ports.removeAll(takenPorts)
            return takenPorts
        }
    }
}