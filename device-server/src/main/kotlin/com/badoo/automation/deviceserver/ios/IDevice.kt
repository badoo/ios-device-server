package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.InstallResult
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.Future

interface IDevice {
    fun prepareAsync()
    val calabashPort: Int
    val deviceInfo: DeviceInfo
    val mjpegServerPort: Int
    val appiumPort: Int
    val wdaEndpoint: URI
    val fbsimctlEndpoint: URI
    val calabashEndpoint: URI
    val appiumEndpoint: URI
    val udid: UDID
    val ref: DeviceRef
    val deviceState: DeviceState
    val videoRecorder: VideoRecorder
    fun uninstallApplication(bundleId: String, appInstaller: AppInstaller)
    fun status(): SimulatorStatusDTO
    val lastException: Exception?
    fun lastCrashLog(): CrashLog
    fun endpointFor(port: Int): URL
    fun release(reason: String)
    fun delete(reason: String)
    fun installApplication(appInstaller: AppInstaller, appBundleId: String, appBinaryPath: File)
    fun getInstallTask(): Future<InstallResult>?

    fun appInstallationStatus(): Map<String, Any> {
        val task = getInstallTask()
            ?: return mapOf(
                "task_exists" to false,
                "task_complete" to false,
                "success" to false
            )

        val status = mutableMapOf<String, Any>(
            "task_exists" to true,
            "task_complete" to task.isDone,
            "success" to (task.isDone && task.get().isSuccess)
        )

        if (task.isDone && !task.get().isSuccess) {
            status["error_message"] = "${task.get().errorMessage}"
        }

        return status
    }

    val instrumentationAgentLog: File
    val appiumServerLog: File
    fun deleteAppiumServerLog()
    val osLog: ISysLog
    fun listApps(): List<FBSimctlAppInfo>
    val isAppiumEnabled: Boolean
}
