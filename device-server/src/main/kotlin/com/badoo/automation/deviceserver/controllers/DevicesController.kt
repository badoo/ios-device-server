package com.badoo.automation.deviceserver.controllers

import com.badoo.automation.deviceserver.EmptyMap
import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceDTO
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.SimulatorStatusDTO
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

    fun getEndpointFor(ref: DeviceRef, port: Int): Map<String, String> {
        return mapOf("endpoint" to deviceManager.getEndpointFor(ref, port).toString())
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
}