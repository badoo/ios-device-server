package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.JsonMapper
import com.fasterxml.jackson.core.JsonProcessingException

class FBSimctlResponseParser : IFBSimctlResponseParser {
    /**
     * Generic response parsing
     */
    override fun parse(response: String): List<Map<String, Any>> {
        val mapper = JsonMapper()
        return filteredResponseLines(response)
            .map { mapper.fromJson<Map<String, Any>>(it) }
    }

    override fun parseFailures(response: String): List<Map<String, Any>> {
        val failureEvents = filteredResponseLines(response)
            .filter { isOfEventName(it, "failure") }

        val jsonMapper = JsonMapper()
        return failureEvents.map { jsonMapper.fromJson<Map<String, Any>>(it) }
    }

    /**
     * Parses device list
     */
    override fun parseDeviceList(response: String): List<FBSimctlDevice>
            = filteredResponseLines(response)
            .filter { isOfEventName(it, "list") }
            .map { fromJson(it, FBSimctlDeviceListResponse::class.java) }
            .map { it.subject }

    override fun parseDeviceSets(response: String): List<String> {
        val deviceSetsEvent = filteredResponseLines(response)
            .first { isOfEventName(it, "list_device_sets") }

        return parse(deviceSetsEvent).map { it["subject"] as String }
    }

    /**
     * Parses applications list
     */
    override fun parseApplicationsList(response: String): List<FBSimctlAppInfo>
            = filteredResponseLines(response)
            .filter { isOfEventName(it, "list_apps") }
            .map { fromJson(it, FBSimctlAppListResponse::class.java) }
            .map { it.subject }.flatten()

    override fun parseDiagnosticInfo(response: String): FBSimctlDeviceDiagnosticInfo {
        val jsonMapper = JsonMapper()
        val diagnosticEvents = filteredResponseLines(response)
            .filter { isOfEventName(it, "diagnostic") }
            .map { jsonMapper.fromJson<Map<String, Any>>(it) }

        val sysLog = getFileLocation(diagnosticEvents, "system_log")
        val coreSimulatorLog = getFileLocation(diagnosticEvents, "coresimulator")
        val videoRecording = getFileLocation(diagnosticEvents, "video")

        return FBSimctlDeviceDiagnosticInfo(
                sysLogLocation = sysLog,
                coreSimulatorLogLocation = coreSimulatorLog,
                videoLocation = videoRecording
        )
    }

    override fun parseDeviceCreation(response: String): FBSimctlDevice {
        val parsedResponse: FBSimctlCreateDeviceResponse?

        try {
            parsedResponse = response.lines()
                    .filter { !isLogEvent(it) }
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

    override fun parseInstallApp(response: String): FBSimctlInstallResult {
        val mapper = JsonMapper()
        val events = response.lines()
            .asSequence()
            .filter { !isLogEvent(it) }
            .map { mapper.fromJson<Map<String, String>>(it) }
            .toList()

        val failureEvent = events.find {
            it["event_name"] == "failure"
        }

        val errorMessage = if (failureEvent != null) {
            failureEvent["subject"] ?: response
        } else {
            ""
        }

        val isInstalled = failureEvent == null && events.any {
            it["event_type"] == "ended" && it["event_name"] == "install"
        }

        return FBSimctlInstallResult(isInstalled, errorMessage)
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

    /**
     * Filter log lines
     */
    private fun isLogEvent(it: String) = it.contains(""""event_name":"log"""")


    /**
     * Sanitize fbsimctl response
     */
    private fun filteredResponseLines(response: String): List<String> {
        return response.lines()
                .filter { isDiscreteEvent(it) }
                .filter { !isLogEvent(it) }
    }

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
