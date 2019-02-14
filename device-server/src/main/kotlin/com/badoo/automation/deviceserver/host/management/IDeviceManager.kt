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
    fun setPermissions(ref: DeviceRef, permissions: AppPermissionsDto)
    fun getEndpointFor(ref: DeviceRef, port: Int): URL
    fun crashLogs(ref: DeviceRef, pastMinutes: Long?): List<CrashLog>
    fun deleteCrashLogs(ref: DeviceRef): Boolean
    fun getLastCrashLog(ref: DeviceRef): CrashLog
    fun shake(ref: DeviceRef)
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
    fun isReady(): Boolean
    fun listFiles(ref: DeviceRef, dataPath: DataPath): List<String>
    fun pullFile(ref: DeviceRef, dataPath: DataPath): ByteArray
    fun uninstallApplication(ref: String, bundleId: String)
}
