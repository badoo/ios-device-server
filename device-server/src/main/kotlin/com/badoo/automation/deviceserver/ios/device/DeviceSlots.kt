package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.DesiredCapabilitiesMatcher
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.host.management.errors.OverCapacityException
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.util.concurrent.ConcurrentLinkedQueue
import java.io.File

class DeviceSlots(
    val remote: IRemote,
    private val wdaRunnerXctest: File,
    private val portAllocator: PortAllocator,
    private val deviceInfoProvider: DeviceInfoProvider,
    knownDevicesList: List<KnownDevice>
) {
    private val activeSlots = mutableListOf<DeviceSlot>()

    private val dcMatcher = DesiredCapabilitiesMatcher()

    private val removedSlots = ConcurrentLinkedQueue<RemovedSlot>()

    private val knownDevices = knownDevicesList.map { it.udid to it }.toMap()

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private fun getDevicesWithRetry(): List<DeviceInfo> {
        val maxAttempts = 3
        var connectedDevices = emptyList<DeviceInfo>()

        for (attempt in 1..maxAttempts) {
            connectedDevices = deviceInfoProvider.list()

            if (connectedDevices.isEmpty()) {
                logger.warn("fbsimctl returned an empty list of devices on attempt $attempt/$maxAttempts")
                Thread.sleep(500)
                continue
            } else {
                break
            }
        }

        return connectedDevices
    }

    fun registerDevices() {
        val connectedDevices = getDevicesWithRetry()
        val knownConnectedDevices = connectedDevices.filter { isWhitelisted(it.udid) }

        val diff = diff(knownConnectedDevices)

        if (diff.removed.isNotEmpty()) {
            logger.info(logMarker, "Will remove ${diff.removed} devices")
            diff.removed.forEach {
                removeSlotBy(it)
            }
        }

        if (diff.added.isNotEmpty()) {
            logger.info(logMarker, "Will add ${diff.added} devices")
            knownConnectedDevices.filter { diff.added.contains(it.udid) }.forEach {
                addSlot(it)
//                Thread.sleep(30000)
            }
        }
    }

    fun getSlot(udid: UDID): DeviceSlot {
        val slot = tryGetSlot(udid = udid)

        if (slot == null) {
            if (removedSlots.any { it.udid == udid }) {
                throw(DeviceNotFoundException("Device $udid was removed because it disconnected"))
            } else {
                throw(DeviceNotFoundException("Device $udid not found"))
            }
        }

        return slot
    }

    fun tryGetSlot(udid: UDID): DeviceSlot? {
        return activeSlots.firstOrNull { it.device.udid == udid }
    }

    fun reserve(desiredCapabilities: DesiredCapabilities): DeviceSlot {
        val slot = availableSlots(desiredCapabilities = desiredCapabilities).firstOrNull()

        if (slot == null) {
            val unused = activeSlots.filter { !it.isReserved() }.map { it.device.deviceInfo }.toList()
            throw OverCapacityException("No unused devices matched $desiredCapabilities. Unused devices: $unused")
        }

        slot.reserve()
        return slot
    }

    fun release(slotUdid: UDID) {
        val slot = getSlot(udid = slotUdid)
        slot.release()
    }

    fun countUnusedSlots(desiredCapabilities: DesiredCapabilities): Int {
        return availableSlots(desiredCapabilities = desiredCapabilities).size
    }

    fun totalCapacity(desiredCapabilities: DesiredCapabilities): Int {
        return activeSlots.count {  dcMatcher.isMatch(it.device.deviceInfo, desiredCapabilities) }
    }

    fun dispose() {
        activeSlots.forEach {
            it.device.release("Disposing")
        }
        activeSlots.clear()
    }

    private fun isWhitelisted(udid: UDID): Boolean {
        return knownDevices.isEmpty() || knownDevices.containsKey((udid))
    }

    private fun availableSlots(desiredCapabilities: DesiredCapabilities): List<DeviceSlot> {
        return activeSlots.filter {
            !it.isReserved() && dcMatcher.isMatch(it.device.deviceInfo, desiredCapabilities)
        }.toList()
    }

    private data class Diff(val added: Set<UDID>, val removed: Set<UDID>)

    private fun diff(deviceInfos: List<DeviceInfo>): Diff {
        val current = activeSlots.map { it.device.udid }.toSet()
        val new = deviceInfos.map { it.udid }.toSet()

        val added = new - current
        val removed = current - new

        return Diff(added, removed)
    }

    private fun addSlot(deviceInfo: DeviceInfo) {
        val udid = deviceInfo.udid

        if (activeSlots.any { it.device.udid == udid }) {
            throw RuntimeException("Device $udid is already registered")
        }

        val allocatedPorts = portAllocator.allocateDAP()

        val device = Device(
            remote =remote,
            deviceInfo = deviceInfo,
            userPorts = allocatedPorts,
            wdaRunnerXctest = wdaRunnerXctest
        )

        device.prepareAsync()

        val slot = DeviceSlot(device = device)
        activeSlots.add(slot)
    }

    private fun removeSlotBy(udid: UDID) {
        val slot = activeSlots.find { it.device.udid == udid }

        if (slot == null) {
            throw DeviceNotFoundException("Device $udid is already unregistered")
        }

        removeSlot(slot)
    }

    private fun removeSlot(slot: DeviceSlot) {
        val allocatedPorts = slot.device.userPorts

        slot.device.release("Removing slot")
        portAllocator.deallocateDAP(allocatedPorts)
        activeSlots.remove(slot)

        removedSlots.add(RemovedSlot(slot.device.udid))
        if (removedSlots.size > 1000) {
            removedSlots.remove()
        }
    }

    private data class RemovedSlot(val udid: UDID)
}
