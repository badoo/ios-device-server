package com.badoo.automation.deviceserver.ios.fbsimctl

interface IFBSimctlResponseParser {
    fun parseDeviceList(response: String): List<FBSimctlDevice>
    fun parseApplicationsList(response: String): List<FBSimctlAppInfo>
    fun parseDiagnosticInfo(response: String): FBSimctlDeviceDiagnosticInfo
    fun parseDeviceCreation(response: String, isTransitional: Boolean): FBSimctlDevice
    fun parse(response: String): List<Map<String, Any>>
    fun parseDeviceSets(response: String): List<String>
}