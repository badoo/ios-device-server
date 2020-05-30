package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import java.io.File
import java.net.URL

interface ISimulatorsNode {
    fun supports(desiredCaps: DesiredCapabilities): Boolean

    fun resetAsync(deviceRef: DeviceRef)

    fun approveAccess(deviceRef: DeviceRef, bundleId: String)
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

    fun addMedia(deviceRef: DeviceRef, fileName: String, data: ByteArray)
    fun syslog(deviceRef: DeviceRef): File
    fun syslogStart(deviceRef: DeviceRef, predicateString: String)
    fun syslogStop(deviceRef: DeviceRef)
    fun syslogDelete(deviceRef: DeviceRef)
    fun resetMedia(deviceRef: DeviceRef)
    fun listMedia(deviceRef: DeviceRef): String
    fun listPhotoData(deviceRef: DeviceRef): String

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
    fun uninstallApplication(deviceRef: DeviceRef, bundleId: String)
    fun setEnvironmentVariables(deviceRef: DeviceRef, envs: Map<String, String>)
    fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String)
    fun installApplication(deviceRef: DeviceRef, appBundleDto: AppBundleDto)
    fun appInstallationStatus(deviceRef: DeviceRef): Map<String, Boolean>
    fun updateApplicationPlist(ref: DeviceRef, plistEntry: PlistEntryDTO)
    val publicHostName: String
    fun deployApplication(appBundle: ApplicationBundle)
}
