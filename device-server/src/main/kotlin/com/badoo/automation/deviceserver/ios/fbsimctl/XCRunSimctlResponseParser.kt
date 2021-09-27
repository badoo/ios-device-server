package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.JsonMapper
import com.fasterxml.jackson.core.JsonProcessingException


class XCRunSimctlResponseParser : IFBSimctlResponseParser {
//    override fun parseRuntimes(response: String): List<XCSimctlRuntime> {
//        return fromJson(response, XCRuntimesResponse::class.java).runtimes
//    }

    private fun <T> fromJson(string: String, clazz: Class<T>): T {
        try {
            return JsonMapper().fromJson(string, clazz)
        } catch (e: JsonProcessingException) {
            throw FBSimctlResponseParseError("Failed to parse fbsimctl response. " +
                    "Please check maybe response format has changed. DTO class: [${clazz.name}]. FBSimctl response: [$string]", e)
        }
    }

//    private data class XCRuntimesResponse(
//        val runtimes: List<XCSimctlRuntime>
//    )

    override fun parseDeviceList(response: String): List<FBSimctlDevice> {
        TODO("Not yet implemented")
    }

    override fun parseApplicationsList(response: String): List<FBSimctlAppInfo> {
        TODO("Not yet implemented")
    }

    override fun parseDiagnosticInfo(response: String): FBSimctlDeviceDiagnosticInfo {
        TODO("Not yet implemented")
    }

    override fun parseDeviceCreation(response: String): FBSimctlDevice {
        TODO("Not yet implemented")
    }

    override fun parse(response: String): List<Map<String, Any>> {
        TODO("Not yet implemented")
    }

    override fun parseDeviceSets(response: String): List<String> {
        TODO("Not yet implemented")
    }

    override fun parseInstallApp(response: String): FBSimctlInstallResult {
        TODO("Not yet implemented")
    }

    override fun parseFailures(response: String): List<Map<String, Any>> {
        TODO("Not yet implemented")
    }
}
