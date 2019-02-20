package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.*
import java.net.URL

interface ISimulatorsNode {
    fun supports(desiredCaps: DesiredCapabilities): Boolean

    fun resetAsync(deviceRef: DeviceRef)

    fun approveAccess(deviceRef: DeviceRef, bundleId: String)
    fun setPermissions(deviceRef: DeviceRef, appPermissions: AppPermissionsDto)
    fun clearSafariCookies(deviceRef: DeviceRef)
    fun shake(deviceRef: DeviceRef)
    fun endpointFor(deviceRef: DeviceRef, port: Int): URL
    fun lastCrashLog(deviceRef: DeviceRef): CrashLog
    fun crashLogs(deviceRef: DeviceRef, pastMinutes: Long?): List<CrashLog>
    fun deleteCrashLogs(deviceRef: DeviceRef): Boolean
    fun state(deviceRef: DeviceRef): SimulatorStatusDTO

    fun videoRecordingDelete(deviceRef: DeviceRef)
    fun videoRecordingGet(deviceRef: DeviceRef): ByteArray
    fun videoRecordingStart(deviceRef: DeviceRef)
    fun videoRecordingStop(deviceRef: DeviceRef)

    fun listFiles(deviceRef: DeviceRef, dataPath: DataPath): List<String>
    fun pullFile(deviceRef: DeviceRef, dataPath: DataPath): ByteArray

    val remoteAddress: String
    fun isReachable(): Boolean
    fun prepareNode()
    fun count(): Int
    fun list(): List<DeviceDTO>
    fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean
    fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO
    fun totalCapacity(desiredCaps: DesiredCapabilities): Int
    fun capacityRemaining(desiredCaps: DesiredCapabilities): Float
    fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO
    fun dispose()
    fun uninstallApplication(deviceRef: DeviceRef, bundleId: String)
    fun runXcuiTest(deviceRef: DeviceRef, xcuiTestExecutionConfig: XcuiTestExecutionConfig) : XcuiTestExecutionResult
}
