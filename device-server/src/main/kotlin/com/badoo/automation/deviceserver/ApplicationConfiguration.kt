package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.proc.SimulatorWebDriverAgent
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import java.io.File

class ApplicationConfiguration {
    private val wdaSimulatorBundlePathProperty = "wda.bundle.path"
    val wdaSimulatorBundlePath: String = System.getProperty(wdaSimulatorBundlePathProperty)
        ?: throw RuntimeException(
            "Must set system property: -D$wdaSimulatorBundlePathProperty=" +
                    "/ABSOLUTE/PATH/ios/facebook/simulators/WebDriverAgentRunner-Runner.app"
        )
    val wdaBundleId: String = System.getProperty("wda.bundle.id", "com.facebook.WebDriverAgentRunner.xctrunner")

    private val wdaDeviceBundlePathProperty = "wda.device.bundle.path"
    val wdaDeviceBundlePath: String = System.getProperty(wdaDeviceBundlePathProperty)
        ?: throw RuntimeException(
            "Must set system property: -D$wdaDeviceBundlePathProperty=" +
                    "/ABSOLUTE/PATH/ios/facebook/devices/WebDriverAgentRunner-Runner.app"
        )

    private val deviceServerConfigPathProperty = "device.server.config.path"
    val deviceServerConfigPath: String = System.getProperty(deviceServerConfigPathProperty)
        ?: throw RuntimeException("Must set system property: -D$deviceServerConfigPathProperty=./config/.device_config")

    val fbsimctlVersion: String = System.getProperty("fbsimctl.version", "HEAD-d30c2a73")

    val remoteWdaSimulatorBundleRoot = System.getProperty("remote.wda.simulator.bundle.path", "/usr/local/opt/web_driver_agent_simulator")

    val remoteWdaDeviceBundleRoot = System.getProperty("remote.wda.device.bundle.path", "/usr/local/opt/web_driver_agent_device")
    val remoteTestHelperAppBundleRoot = System.getProperty("remote.test.helper.app.bundle.path", "/usr/local/opt/ios-device-server/test_helper_app")
    val useFbsimctlProc = java.lang.Boolean.getBoolean("useFbsimctlProc")
    val tempFolder = File(System.getenv("TMPDIR") ?: System.getenv("TMP") ?: System.getenv("TEMP") ?: System.getenv("PWD"))
    val path = getUnifiedPath()

    val trustStorePath: String = System.getProperty("trust.store.path", "")
    val assetsPath: String = System.getProperty("media.assets.path", "")
    val appBundleCachePath: File = File(System.getProperty("app.bundle.cache.path", System.getenv("HOME")), "app_bundle_cache")
    val appBundleCacheRemotePath: File = File(System.getProperty("app.bundle.cache.remote.path", "/Users/qa/app_bundle_cache"))
    val videoRecorderClassName = System.getProperty("video.recorder", SimulatorVideoRecorder::class.qualifiedName)
    val simulatorWdaClassName = System.getProperty("simulator.wda", SimulatorWebDriverAgent::class.qualifiedName)
    val simulatorBackupPath: String? = System.getProperty("simulator.backup.path")

    private fun getUnifiedPath(): String {
        val originalPath = System.getenv("PATH") ?: "/usr/bin"

        val path: Set<String> = originalPath.split(":").toSet() + setOf(
            "/usr/bin",
            "/usr/sbin",
            "/usr/local/bin",
            "/usr/local/sbin",
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin"
        )

        return path.joinToString(":")
    }
}
