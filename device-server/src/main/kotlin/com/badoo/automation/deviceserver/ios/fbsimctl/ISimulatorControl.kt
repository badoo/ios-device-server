package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.UDID
import java.io.File

interface ISimulatorControl {
    fun installApp(udid: UDID, bundlePath: File)

    /**
     * List simulators
     */
    fun listSimulators(): List<FBSimctlDevice>

    /**
     * List physical devices
     */
    fun listDevices(): List<FBSimctlDevice>

    /**
     * List simulator or physical device matching specified udid
     */
    fun listDevice(udid: UDID): FBSimctlDevice?
    fun listApps(udid: UDID): List<FBSimctlAppInfo>
    /**
     * returns path to device sets
     * E.g. "/Users/qa/Library/Developer/CoreSimulator/Devices"
     */
    fun defaultDeviceSet(): String

    fun eraseSimulator(udid: UDID): String
    fun create(model: String?, os: String?): FBSimctlDevice
    fun diagnose(udid: UDID): FBSimctlDeviceDiagnosticInfo
    fun shutdown(udid: UDID): CommandResult
    fun shutdownAllBooted(): String
    fun delete(udid: UDID): String
    fun terminateApp(udid: UDID, bundleId: String, raiseOnError: Boolean = false): String
    fun uninstallApp(udid: UDID, bundleId: String, raiseOnError: Boolean = true)
    val fbsimctlBinary: String
}
