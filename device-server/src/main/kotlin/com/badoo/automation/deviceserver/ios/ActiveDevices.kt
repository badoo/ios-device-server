package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.data.DeviceDTO
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.NodeRef
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SessionEntry(
        val ref: DeviceRef,
        val node: ISimulatorsNode,
        val userId: String?
)

class ActiveDevices(
        private val sessionId: String = "defaultSessionId",
        private val currentTimeSeconds: ()->Long = ::currentTimeSecondsProvider
) {
    private val devices: MutableMap<DeviceRef, SessionEntry> = ConcurrentHashMap()
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    companion object {
        private val DEFAULT_RELEASE_TIMEOUT: Duration = Duration.ofSeconds(600)
        fun currentTimeSecondsProvider(): Long = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime())
    }

    fun deviceRefs(): Set<DeviceRef> {
        return devices.keys
    }

    fun deviceList(): List<DeviceDTO> {
        val disconnected = mutableListOf<DeviceRef>()

        val list = devices.map {
            try {
                it.value.node.getDeviceDTO(it.value.ref)
            } catch (ex: DeviceNotFoundException) {
                disconnected.add(it.key)
                null
            }
        }.filterNotNull()

        disconnected.forEach { unregisterDeleteDevice(it) }

        return list
    }

    fun registerDevice(ref: DeviceRef, node: ISimulatorsNode, userId: String?) {
        devices[ref] = SessionEntry(ref, node, userId)
    }

    fun unregisterNodeDevices(node: ISimulatorsNode) {
        devices.entries
                .filter { it.value.node == node }
                .forEach { unregisterDeleteDevice(it.key) }
    }

    private fun tryGetNodeFor(ref: DeviceRef): ISimulatorsNode? {
        return devices[ref]?.node
    }

    fun getNodeFor(ref: DeviceRef): ISimulatorsNode {
        val node = tryGetNodeFor(ref)
        if (node == null) {
            throw DeviceNotFoundException("Device [$ref] not found in [$sessionId] activeDevices")
        } else {
            return node
        }
    }

    fun getStatus(): String {
        return devices
                .map { "\n" + it.key to it.value }
                .toString()
    }

    fun unregisterDeleteDevice(ref: DeviceRef) {
        devices.remove(ref)
    }

    fun releaseDevice(ref: DeviceRef, reason: String) {
        val session = sessionByRef(ref)
        session.node.deleteRelease(session.ref, reason)
        unregisterDeleteDevice(session.ref)
    }

    fun releaseDevices(entries: List<DeviceRef>, reason: String) {
        entries.parallelStream().forEach {
            try {
                releaseDevice(it, reason)
            } catch (e: RuntimeException) {
                logger.warn("Failed to release device $it", e)
            }
        }
    }

    fun getUserDeviceRefs(userId: String): List<DeviceRef> {
        return devices.filter { it.value.userId == userId }.map { it.key }
    }

    private fun sessionByRef(ref: String): SessionEntry {
        return devices[ref] ?: throw DeviceNotFoundException("Device [$ref] not found in active devices")
    }

    fun activeDevicesByNode(ref: NodeRef): Map<DeviceRef, SessionEntry> {
        return devices.filter { it.value.node.publicHostName == ref }
    }
}
