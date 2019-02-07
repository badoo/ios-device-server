package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.EmptyMap
import com.badoo.automation.deviceserver.JsonMapper
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.IDeviceManager
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.auth.UserIdPrincipal

class DevicesController(private val deviceManager: IDeviceManager) {
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

    fun releaseDevices(user: UserIdPrincipal) {
        deviceManager.releaseUserDevices(user.name, "httpRequest")
    }

    fun setAccessToCameraAndThings(ref: DeviceRef, jsonContent: JsonNode): EmptyMap {
        jsonContent.elements().forEach { deviceManager.approveAccess(ref, it["bundle_id"].textValue()) }
        return happy
    }

    fun setPermissions(ref: DeviceRef, json: JsonNode): EmptyMap {
        if (json.isArray) {
            return setAccessToCameraAndThings(ref, json)
        }

        val permissions = JsonMapper().fromJson<AppPermissionsDto>(json)
        deviceManager.setPermissions(ref, permissions)

        return happy
    }

    fun getEndpointFor(ref: DeviceRef, port: Int): Map<String, String> {
        return mapOf("endpoint" to deviceManager.getEndpointFor(ref, port).toString())
    }

    fun crashLogs(ref: DeviceRef, pastMinutes: Long?): List<Map<String, String>> {
        var logs = deviceManager.crashLogs(ref, pastMinutes)

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

    fun uninstallApplication(ref: DeviceRef, bundleId: String): EmptyMap {
        deviceManager.uninstallApplication(ref, bundleId)
        return happy
    }

    fun runXcuiTest(ref: DeviceRef, xcuiTestExecutionConfig: XcuiTestExecutionConfig): Map<String, String> {
        return deviceManager.runXcuiTest(ref, xcuiTestExecutionConfig)
    }
}
