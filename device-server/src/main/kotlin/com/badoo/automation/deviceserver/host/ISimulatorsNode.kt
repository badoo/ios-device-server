package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.util.regex.Pattern

interface ISimulatorsNode {
    fun supports(desiredCaps: DesiredCapabilities): Boolean

    fun resetAsync(deviceRef: DeviceRef)

    fun sendPushNotification(deviceRef: DeviceRef, bundleId: String, notificationContent: ByteArray)
    fun sendPasteboard(deviceRef: DeviceRef, payload: ByteArray)
    fun setPermissions(deviceRef: DeviceRef, appPermissions: AppPermissionsDto)
    fun clearSafariCookies(deviceRef: DeviceRef)
    fun shake(deviceRef: DeviceRef)
    fun openUrl(deviceRef: DeviceRef, url: String)
    fun endpointFor(deviceRef: DeviceRef, port: Int): URL
    fun lastCrashLog(deviceRef: DeviceRef): CrashLog
    fun crashLogs(deviceRef: DeviceRef, pastMinutes: Long?): List<CrashLog>
    fun crashLogs(deviceRef: DeviceRef, appName: String?): List<CrashLog>
    fun deleteCrashLogs(deviceRef: DeviceRef): Boolean
    fun state(deviceRef: DeviceRef): SimulatorStatusDTO

    fun videoRecordingDelete(deviceRef: DeviceRef)
    fun videoRecordingGet(deviceRef: DeviceRef): ByteArray
    fun videoRecordingStart(deviceRef: DeviceRef)
    fun videoRecordingStop(deviceRef: DeviceRef)

    fun listFiles(deviceRef: DeviceRef, dataPath: DataPath): List<String>
    fun pullFile(deviceRef: DeviceRef, dataPath: DataPath): ByteArray
    fun pullFile(deviceRef: DeviceRef, path: Path): ByteArray

    fun addMedia(deviceRef: DeviceRef, fileName: String, data: ByteArray)
    fun syslog(deviceRef: DeviceRef): File
    fun deviceAgentLog(deviceRef: DeviceRef): File
    fun deviceAgentLogDelete(deviceRef: DeviceRef)
    fun appiumServerLog(deviceRef: DeviceRef): File
    fun appiumServerLogDelete(deviceRef: DeviceRef)
    fun syslogStart(deviceRef: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions)
    fun syslogStop(deviceRef: DeviceRef)
    fun syslogDelete(deviceRef: DeviceRef)
    fun resetMedia(deviceRef: DeviceRef)
    fun listMedia(deviceRef: DeviceRef): List<String>
    fun listPhotoData(deviceRef: DeviceRef): List<String>

    fun getDiagnostic(deviceRef: DeviceRef, type: DiagnosticType, query: DiagnosticQuery): Diagnostic
    fun resetDiagnostic(deviceRef: DeviceRef, type: DiagnosticType)

    val remoteAddress: String
    fun isReachable(): Boolean
    fun prepareNode()
    fun list(): List<DeviceDTO>
    fun deleteRelease(deviceRef: DeviceRef, reason: String): Boolean
    fun getDeviceDTO(deviceRef: DeviceRef): DeviceDTO
    fun totalCapacity(desiredCaps: DesiredCapabilities): Int
    fun capacityRemaining(desiredCaps: DesiredCapabilities): Float
    fun createDeviceAsync(desiredCaps: DesiredCapabilities): DeviceDTO
    fun dispose()
    fun reboot()
    fun uninstallApplication(deviceRef: DeviceRef, bundleId: String)
    fun deleteAppData(deviceRef: DeviceRef, bundleId: String)
    fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>)
    fun getEnvironmentVariable(deviceRef: DeviceRef, variableName: String): String
    fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String)
    fun pushFile(ref: DeviceRef, data: ByteArray, path: Path)
    fun deleteFile(ref: DeviceRef, path: Path)
    fun installApplication(deviceRef: DeviceRef, appBundleDto: AppBundleDto)
    fun appInstallationStatus(deviceRef: DeviceRef): Map<String, Boolean>
    fun updateApplicationPlist(ref: DeviceRef, plistEntry: PlistEntryDTO)
    val publicHostName: String
    fun deployApplication(appBundle: ApplicationBundle)
    fun listApps(deviceRef: DeviceRef): List<FBSimctlAppInfo>
    fun locationListScenarios(deviceRef: DeviceRef): List<String>
    fun locationClear(deviceRef: DeviceRef)
    fun locationSet(deviceRef: DeviceRef, latitude: Double, longitude: Double)
    fun locationRunScenario(deviceRef: DeviceRef, scenarioName: String)
    fun locationStartLocationSequence(deviceRef: DeviceRef, speed: Int, distance: Int, interval: Int, waypoints: List<LocationDto>)
    fun getNodeInfo(): NodeInfo
}

data class NodeInfo(
    val currentDate: String,
    val uptime: String,
    val bootTime: Long,
    val info: List<String>
) {
    companion object {
        private val bootTimeSplitPattern = Pattern.compile("[ ,]")
        val numberRegex = Regex("\\d+")

        fun getNodeInfo(remote: IRemote): NodeInfo {
            val uptimeInfo = remote.shell("/bin/date ; /usr/bin/uptime ; /usr/sbin/sysctl -n kern.boottime")
                .stdOut.trim().lines()
            val currentDate = uptimeInfo[0]
            val uptime = uptimeInfo[1]
            val bootTime = uptimeInfo[2].split(bootTimeSplitPattern).first { it.matches(numberRegex) }.toLong()

            val nodeInfo = remote.shell("/usr/sbin/system_profiler SPHardwareDataType").stdOut.trim().lines()

            return NodeInfo(
                currentDate = currentDate,
                uptime = uptime,
                bootTime = bootTime,
                info = nodeInfo
            )
        }
    }
}
