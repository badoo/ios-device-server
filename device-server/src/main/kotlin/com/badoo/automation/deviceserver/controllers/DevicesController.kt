package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.EmptyMap
import com.badoo.automation.deviceserver.JsonMapper
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.auth.UserIdPrincipal
import java.io.File
import java.nio.file.Path

class DevicesController(private val deviceManager: DeviceManager) {
    private val happy = emptyMap<Unit, Unit>()

    fun getDeviceRefs(): List<DeviceDTO> {
        return deviceManager.getDeviceRefs()
    }

    fun createDevice(desiredCapabilities: DesiredCapabilities, user: UserIdPrincipal?): DeviceDTO {
        return deviceManager.createDeviceAsync(desiredCapabilities, user?.name)
    }

    fun getDeviceContactDetails(ref: DeviceRef): DeviceDTO {
        return deviceManager.getGetDeviceDTO(ref)
    }

    fun controlDevice(ref: DeviceRef, jsonContent: JsonNode): EmptyMap {
        val action = jsonContent["action"]?.asText()
        when (action) {
            "reset" -> deviceManager.resetAsyncDevice(ref)
            "clear_safari_cookies" -> deviceManager.clearSafariCookies(ref)
            "shake" -> deviceManager.shake(ref)
            else -> throw IllegalArgumentException("Unknown action $action")
        }
        return happy
    }

    fun deleteReleaseDevice(ref: DeviceRef): EmptyMap {
        deviceManager.deleteReleaseDevice(ref, "httpRequest")
        return happy
    }

    fun deleteDevice(ref: DeviceRef): EmptyMap {
        deviceManager.deleteDevice(ref, "httpRequest")
        return happy
    }

    fun releaseDevices(user: UserIdPrincipal) {
        deviceManager.releaseUserDevices(user.name, "httpRequest")
    }

    fun releaseAllDevices() {
        deviceManager.releaseAllDevices("httpRequest")
    }

    /**
     * List available simulation scenarios
     */
    fun locationListScenarios(ref: DeviceRef): List<String> {
        return deviceManager.locationListScenarios(ref)
    }

    /**
     * Stop any running scenario and clear any simulated location
     */
    fun locationClear(ref: DeviceRef) {
        deviceManager.locationClear(ref)
    }

    /**
     * Set the location to a specific latitude and longitude
     */
    fun locationSet(ref: DeviceRef, latitude: Double, longitude: Double) {
        deviceManager.locationSet(ref, latitude, longitude)
    }

    /**
     * Run a simulated location scenario (use the list action to get a list of scenarios)
     */
    fun locationRunScenario(ref: DeviceRef, scenarioName: String) {
        deviceManager.locationRunScenario(ref, scenarioName)
    }

    /**
     * Run a custom location scenario
     */
    fun locationStartLocationSequence(ref: DeviceRef, speed: Int, distance: Int, interval: Int, waypoints: List<LocationDto>) {
        deviceManager.locationStartLocationSequence(ref, speed, distance, interval, waypoints)
    }

    fun sendPushNotification(ref: DeviceRef, bundleId: String, notificationContent: ByteArray): EmptyMap {
        deviceManager.sendPushNotification(ref, bundleId, notificationContent)
        return happy
    }

    fun sendPasteboard(ref: DeviceRef, payload: ByteArray): EmptyMap {
        deviceManager.sendPasteboard(ref, payload)
        return happy
    }

    fun setPermissions(ref: DeviceRef, json: JsonNode): EmptyMap {
        val permissions = JsonMapper().fromJson<AppPermissionsDto>(json)
        deviceManager.setPermissions(ref, permissions)

        return happy
    }

    fun getEndpointFor(ref: DeviceRef, port: Int): Map<String, String> {
        return mapOf("endpoint" to deviceManager.getEndpointFor(ref, port).toString())
    }

    fun crashLogs(ref: DeviceRef, pastMinutes: Long?): List<Map<String, String>> {
        val logs = deviceManager.crashLogs(ref, pastMinutes)

        return logs.map {
            mapOf("filename" to it.filename, "content" to it.content)
        }
    }

    fun listApps(ref: DeviceRef): List<FBSimctlAppInfo> = deviceManager.listApps(ref)

    fun crashLogs(ref: DeviceRef, appName: String?): List<Map<String, String>> {
        val logs = deviceManager.crashLogs(ref, appName)

        return logs.map {
            mapOf("filename" to it.filename, "content" to it.content)
        }
    }

    fun deleteCrashLogs(ref: DeviceRef): Boolean {
        return deviceManager.deleteCrashLogs(ref)
    }

    fun getLastCrashLog(ref: DeviceRef): Map<String, String> {
        val log = deviceManager.getLastCrashLog(ref)
        return mapOf("filename" to log.filename, "content" to log.content)
    }

    fun startStopVideo(ref: DeviceRef, jsonContent: JsonNode): EmptyMap {
        val start = jsonContent["start"]
        if (start.isBoolean) {
            when (start.asBoolean()) {
                true -> deviceManager.startVideo(ref)
                false -> deviceManager.stopVideo(ref)
            }
        } else {
            throw IllegalArgumentException("Parameter start should be boolean true or false")
        }
        return happy
    }

    fun getVideo(ref: DeviceRef): ByteArray {
        return deviceManager.getVideo(ref)
    }

    fun deleteVideo(ref: DeviceRef): EmptyMap {
        deviceManager.deleteVideo(ref)
        return happy
    }

    fun listMedia(ref: DeviceRef): List<String> {
        return deviceManager.listMedia(ref)
    }

    fun listPhotoData(ref: DeviceRef): List<String> {
        return deviceManager.listPhotoData(ref)
    }

    fun resetMedia(ref: DeviceRef): EmptyMap {
        deviceManager.resetMedia(ref)

        return happy
    }

    fun addMedia(ref: DeviceRef, fileName: String, data: ByteArray): EmptyMap {
        deviceManager.addMedia(ref, fileName, data)
        return happy
    }

    fun syslog(ref: DeviceRef): File {
        return deviceManager.syslog(ref)
    }

    fun instrumentationAgentLog(ref: DeviceRef): File {
        return deviceManager.instrumentationAgentLog(ref)
    }

    fun deleteInstrumentationAgentLog(ref: DeviceRef) {
        deviceManager.deleteInstrumentationAgentLog(ref)
    }

    fun appiumServerLog(ref: DeviceRef): File {
        return deviceManager.appiumServerLog(ref)
    }

    fun deleteAppiumServerLog(ref: DeviceRef) {
        deviceManager.deleteAppiumServerLog(ref)
    }

    fun syslogDelete(ref: DeviceRef): EmptyMap {
        deviceManager.syslogDelete(ref)
        return happy
    }

    fun syslogStart(ref: DeviceRef, sysLogCaptureOptions: SysLogCaptureOptions): EmptyMap {
        deviceManager.syslogStart(ref, sysLogCaptureOptions)
        return happy
    }

    fun syslogStop(ref: DeviceRef): EmptyMap {
        deviceManager.syslogStop(ref)
        return happy
    }

    fun getDiagnostic(ref: DeviceRef, type: String, query: DiagnosticQuery): Diagnostic {
        val diagnosticType = DiagnosticType.fromString(type)
        return deviceManager.getDiagnostic(ref, diagnosticType, query)
    }

    fun resetDiagnostic(ref: DeviceRef, type: String) {
        val diagnosticType = DiagnosticType.fromString(type)
        deviceManager.resetDiagnostic(ref, diagnosticType)
    }

    fun getDeviceState(ref: DeviceRef): SimulatorStatusDTO {
        return deviceManager.getDeviceState(ref)
    }

    fun getTotalCapacity(desiredCapabilities: DesiredCapabilities): Map<String, Int> {
        return deviceManager.getTotalCapacity(desiredCapabilities)
    }

    fun listFiles(ref: DeviceRef, dataPath: DataPath): List<String> {
        return deviceManager.listFiles(ref, dataPath)
    }

    fun pullFile(ref: DeviceRef, dataPath: DataPath): ByteArray {
        return deviceManager.pullFile(ref, dataPath)
    }

    fun pullFile(ref: DeviceRef, path: Path): ByteArray {
        return deviceManager.pullFile(ref, path)
    }

    fun pushFile(ref: DeviceRef, fileName: String, data: ByteArray, bundleId: String): EmptyMap {
        deviceManager.pushFile(ref, fileName, data, bundleId)
        return happy
    }

    fun pushFile(ref: DeviceRef, data: ByteArray, path: Path): EmptyMap {
        deviceManager.pushFile(ref, data, path)
        return happy
    }

    fun deleteFile(ref: DeviceRef, path: Path): EmptyMap {
        deviceManager.deleteFile(ref, path)
        return happy
    }

    fun openUrl(ref: DeviceRef, url: String) {
        return deviceManager.openUrl(ref, url)
    }

    fun uninstallApplication(ref: DeviceRef, bundleId: String): EmptyMap {
        deviceManager.uninstallApplication(ref, bundleId)
        return happy
    }

    fun deleteAppData(ref: DeviceRef, bundleId: String): EmptyMap {
        deviceManager.deleteAppData(ref, bundleId)
        return happy
    }

    fun setEnvironmentVariables(ref: DeviceRef, environmentVariables: Map<String, String>): EmptyMap {
        deviceManager.setEnvironmentVariables(ref, environmentVariables)
        return happy
    }

    fun getEnvironmentVariable(ref: DeviceRef, variableName: String): String {
        return deviceManager.getEnvironmentVariable(ref, variableName)
    }

    fun deployApplication(appBundleDto: AppBundleDto): EmptyMap {
        deviceManager.deployApplication(appBundleDto)
        return happy
    }

    fun installApplication(ref: String, appBundleDto: AppBundleDto): EmptyMap {
        deviceManager.installApplication(ref, appBundleDto)
        return happy
    }

    fun appInstallationStatus(ref: String): Map<String, Any> {
        return deviceManager.appInstallationStatus(ref)
    }

    fun updateApplicationPlist(deviceRef: String, plistEntry: PlistEntryDTO): Any {
        deviceManager.updateApplicationPlist(deviceRef, plistEntry)
        return happy
    }
}
