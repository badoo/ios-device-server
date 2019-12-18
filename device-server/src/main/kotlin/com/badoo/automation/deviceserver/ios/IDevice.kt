package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import java.io.File
import java.net.URI
import java.net.URL

interface IDevice {
    fun prepareAsync()
    val calabashPort: Int
    val deviceInfo: DeviceInfo
    val mjpegServerPort: Int
    val wdaEndpoint: URI
    val fbsimctlEndpoint: URI
    val udid: UDID
    val ref: DeviceRef
    val deviceState: DeviceState
    val userPorts: DeviceAllocatedPorts
    val videoRecorder: VideoRecorder
    fun uninstallApplication(bundleId: String, appInstaller: AppInstaller)
    fun status(): SimulatorStatusDTO
    val lastException: Exception?
    fun lastCrashLog(): CrashLog
    fun endpointFor(port: Int): URL
    fun release(reason: String)
    fun installApplication(appInstaller: AppInstaller, appBundleId: String, appBinaryPath: File)
    fun appInstallationStatus(): Map<String, Boolean>
}
