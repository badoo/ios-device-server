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
        var updatedAtSeconds: Long,
        val releaseTimeout: Duration,
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

    fun registerDevice(ref: DeviceRef, node: ISimulatorsNode, releaseTimeout: Duration, userId: String?) {
        devices[ref] = SessionEntry(ref, node, currentTimeSeconds(), releaseTimeout, userId)
    }

    fun unregisterNodeDevices(node: ISimulatorsNode) {
        devices.entries
                .filter { it.value.node == node }
                .forEach { unregisterDeleteDevice(it.key) }
    }

    private fun refreshSessionEntry(sessionEntry: SessionEntry) {
        sessionEntry.updatedAtSeconds = currentTimeSeconds()
    }

    private fun tryGetNodeFor(ref: DeviceRef): ISimulatorsNode? {
        val sessionEntry = devices[ref]
        if (sessionEntry != null) {
            refreshSessionEntry(sessionEntry)
            return sessionEntry.node
        } else {
            return null
        }
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

    fun readyForRelease(): List<DeviceRef> {
        val secondsNow = currentTimeSeconds()
        return devices.filter { (_, session) -> secondsNow - session.updatedAtSeconds >= session.releaseTimeout.seconds }
                .map { (deviceRef, _) -> deviceRef }
                .also { logger.info("Ready to release $it"); }
    }

    fun nextReleaseAtSeconds(): Long {
        val sessionEntry = devices
            .map { it.value }
            .minBy { it.updatedAtSeconds + it.releaseTimeout.seconds }

        val nextReleaseAtSeconds = if (sessionEntry == null) {
            currentTimeSeconds() + DEFAULT_RELEASE_TIMEOUT.seconds
        } else {
            sessionEntry.updatedAtSeconds + sessionEntry.releaseTimeout.seconds
        }

        logger.info("nextReleaseAtSeconds = $nextReleaseAtSeconds seconds")
        return nextReleaseAtSeconds
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
