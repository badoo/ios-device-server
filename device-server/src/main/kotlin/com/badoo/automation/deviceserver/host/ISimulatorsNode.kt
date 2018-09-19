package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.*
import java.net.URL

interface ISimulatorsNode {
    fun supports(desiredCaps: DesiredCapabilities): Boolean

    fun resetAsync(deviceRef: DeviceRef)

    fun approveAccess(deviceRef: DeviceRef, bundleId: String)
    fun clearSafariCookies(deviceRef: DeviceRef)
    fun shake(deviceRef: DeviceRef)
    fun endpointFor(deviceRef: DeviceRef, port: Int): URL
    fun lastCrashLog(deviceRef: DeviceRef): CrashLog
    fun state(deviceRef: DeviceRef): SimulatorStatusDTO

    fun videoRecordingDelete(deviceRef: DeviceRef)
    fun videoRecordingGet(deviceRef: DeviceRef): ByteArray
    fun videoRecordingStart(deviceRef: DeviceRef)
    fun videoRecordingStop(deviceRef: DeviceRef)

    fun listFiles(deviceRef: DeviceRef, dataPath: DataPath): List<String>
    fun pullFile(deviceRef: DeviceRef, dataPath: DataPath): ByteArray

    val remoteAddress: String

    // Simple reachability test
    fun isReachable(): Boolean

    // Initial setting up: ensure support files copied over, local server configured with emulator count, etc.
    fun prepareNode()

    fun count(): Int
    fun list(): List<DeviceDTO>

    // Relinquish this device.
    fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean

    // Data Transfer Object of device
    fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO

    // How many devices supporting the desired capabilities this node could theoretically provide.
    fun totalCapacity(desiredCaps: DesiredCapabilities): Int

    // Fraction of devices still available: unused/(used+unused)
    fun capacityRemaining(desiredCaps: DesiredCapabilities): Float

    // Arrange for the device to be created at some point in the future - possibly on another thread.
    fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO
    fun dispose()
}

