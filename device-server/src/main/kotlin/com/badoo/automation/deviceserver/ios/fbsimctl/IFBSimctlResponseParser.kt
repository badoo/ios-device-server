package com.badoo.automation.deviceserver.ios.fbsimctl

interface IFBSimctlResponseParser {
    fun parseDeviceList(response: String): List<FBSimctlDevice>
    fun parseApplicationsList(response: String): List<FBSimctlAppInfo>
    fun parseDiagnosticInfo(response: String): FBSimctlDeviceDiagnosticInfo
    fun parseDeviceCreation(response: String): FBSimctlDevice
    fun parse(response: String): List<Map<String, Any>>
    fun parseDeviceSets(response: String): List<String>
    fun parseInstallApp(response: String): FBSimctlInstallResult
    fun parseFailures(response: String): List<Map<String, Any>>
}
