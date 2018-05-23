package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.JsonMapper
import com.fasterxml.jackson.core.JsonProcessingException

class FBSimctlResponseParser : IFBSimctlResponseParser {
    /**
     * Generic response parsing
     */
    override fun parse(response: String): List<Map<String, Any>> {
        val mapper = JsonMapper()
        return response.lines()
            .filter { isDiscreteEvent(it) }
            .map { mapper.fromJson<Map<String, Any>>(it) }
    }

    /**
     * Parses device list
     */
    override fun parseDeviceList(response: String): List<FBSimctlDevice>
            = response.lines()
            .filter { isDiscreteEvent(it) }
            .filter { isOfEventName(it, "list") }
            .map { fromJson(it, FBSimctlDeviceListResponse::class.java) }
            .map { it.subject }

    override fun parseDeviceListHttp(response: String): FBSimctlDevice
            = fromJson(response, FBSimctlDeviceListHttpResponse::class.java)
            .subject.first().subject

    /**
     * Parses applications list
     */
    override fun parseApplicationsList(response: String): List<FBSimctlAppInfo>
            = response.lines()
            .filter { isDiscreteEvent(it) }
            .filter { isOfEventName(it, "list_apps") }
            .map { fromJson(it, FBSimctlAppListResponse::class.java) }
            .map { it.subject }.flatten()

    override fun parseDiagnosticInfo(response: String): FBSimctlDeviceDiagnosticInfo {
        val result = parse(response)

        val sysLog = getFileLocation(result, "system_log")
        val coreSimulatorLog = getFileLocation(result, "coresimulator")
        val videoRecording = getFileLocation(result, "video")

        return FBSimctlDeviceDiagnosticInfo(
                sysLogLocation = sysLog,
                coreSimulatorLogLocation = coreSimulatorLog,
                videoLocation = videoRecording
        )
    }

    override fun parseDeviceCreation(response: String, isTransitional: Boolean): FBSimctlDevice {
        val parsedResponse: FBSimctlCreateDeviceResponse?

        try {
            parsedResponse = response.lines()
                    .filter { isOfEventName(it, "create") }
                    .filter { isEnded(it) }
                    .map { fromJson(it, FBSimctlCreateDeviceResponse::class.java) }
                    .firstOrNull { it.event_name == "create" && it.event_type == "ended" }
        } catch (e: RuntimeException) {
            throw FBSimctlResponseParseError("Failed to parse 'device create' response [$response]", e)
        }

        if (parsedResponse == null) {
            throw FBSimctlError("Failed to parse 'device create' response: [$response]")
        }

        return parsedResponse.subject
    }

    private fun getFileLocation(result: List<Map<String, Any>>, fileType: String): String? {
        val found = result
                .map {
                    @Suppress("UNCHECKED_CAST")
                    it["subject"] as Map<String, String>
                }
                .find { it["short_name"] == fileType }

        return found?.get("location")
    }
    //region private fun and stuff

    /**
     * Filter junk lines with other events
     */
    private fun isDiscreteEvent(it: String) = it.contains(":\"discrete\"")

    //
    /**
     * Filter events by name
     * FIXME: quick workaround added to skip extraneous events and prevent parser from failing,
     * refactor parsing so that it determines class based on event_name or skips non expected class events
     */
    private fun isOfEventName(it: String, name: String) = it.contains(":\"$name\"")

    private fun isEnded(it: String) = it.contains("\"event_type\":\"ended\"")

    private fun <T> fromJson(string: String, clazz: Class<T>): T {
        try {
            return JsonMapper().fromJson(string, clazz)
        } catch (e: JsonProcessingException) {
            throw FBSimctlResponseParseError("Failed to parse fbsimctl response. " +
                    "Please check maybe response format has changed. DTO class: [${clazz.name}]. FBSimctl response: [$string]", e)
        }
    }

    private data class FBSimctlAppListResponse(
            val event_name: String,
            val timestamp: String,
            val subject: List<FBSimctlAppInfo>,
            val event_type: String
    )

    private data class FBSimctlDeviceListResponse(
            val event_name: String,
            val timestamp: String,
            val subject: FBSimctlDevice,
            val event_type: String
    )

    private data class FBSimctlDeviceListHttpResponse(
            val status: String,
            val subject: List<FBSimctlDeviceListResponse>,
            val events: List<Any?>
    )

    private data class FBSimctlCreateDeviceResponse(
            val event_name: String,
            val timestamp: String,
            val subject: FBSimctlDevice,
            val event_type: String
    )
    //endregion
}
