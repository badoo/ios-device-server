package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.*
import java.net.URL

interface IDeviceManager {
    fun getDeviceRefs() : List<DeviceDTO>
    fun createDeviceAsync(desiredCaps: DesiredCapabilities, userId: String?): DeviceDTO
    fun deleteReleaseDevice(ref: DeviceRef, reason: String)
    fun getGetDeviceDTO(ref: DeviceRef): DeviceDTO
    fun clearSafariCookies(ref: DeviceRef)
    fun resetAsyncDevice(ref: DeviceRef)
    fun approveAccess(ref: DeviceRef, bundleId: String)
    fun getEndpointFor(ref: DeviceRef, port: Int): URL
    fun getLastCrashLog(ref: DeviceRef): CrashLog
    fun startVideo(ref: DeviceRef)
    fun stopVideo(ref: DeviceRef)
    fun getVideo(ref: DeviceRef): ByteArray
    fun deleteVideo(ref: DeviceRef)
    fun getDeviceState(ref: DeviceRef): SimulatorStatusDTO
    fun getTotalCapacity(desiredCaps: DesiredCapabilities): Map<String, Int>
    fun nextReleaseAtSeconds(): Long
    fun readyForRelease(): List<DeviceRef>
    fun getStatus(): Map<String, Any>
    fun releaseUserDevices(userId: String, reason: String)
}