package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import java.io.File

class ApplicationConfiguration {
    val wdaDeviceBundles: String = System.getProperty("wda.device.bundles")
        ?: throw RuntimeException("Must set system property: -Dwda.device.bundles=/ABSOLUTE/PATH/ios/facebook/devices/,")

    val wdaSimulatorBundles: String = System.getProperty("wda.simulator.bundles")
        ?: throw RuntimeException("Must set system property: -Dwda.simulator.bundles=/ABSOLUTE/PATH/ios/facebook/simulator/")

    private val deviceServerConfigPathProperty = "device.server.config.path"
    val deviceServerConfigPath: String = System.getProperty(deviceServerConfigPathProperty)
        ?: throw RuntimeException("Must set system property: -D$deviceServerConfigPathProperty=./config/.device_config")

    val fbsimctlVersion: String = System.getProperty("fbsimctl.version", "HEAD-d30c2a73")

    val remoteWdaSimulatorBundleRoot = System.getProperty("remote.wda.simulator.bundle.path", "/usr/local/opt/web_driver_agent_simulator")

    val remoteWdaDeviceBundleRoot = System.getProperty("remote.wda.device.bundle.path", "/usr/local/opt/web_driver_agent_device")
    val remoteTestHelperAppBundleRoot = System.getProperty("remote.test.helper.app.bundle.path", "/usr/local/opt/ios-device-server/test_helper_app")
    val useTestHelperApp = java.lang.Boolean.getBoolean("useTestHelperApp")
    val remoteVideoRecorder = File(System.getProperty("remote.video.recorder.path", "/usr/local/opt/ios-device-server-utils/record_video_x264.sh"))
    val useFbsimctlProc = java.lang.Boolean.getBoolean("useFbsimctlProc")
    val tempFolder = File(System.getenv("TMPDIR") ?: "/tmp")
    val trustStorePath: String = System.getProperty("trust.store.path", "")
    val assetsPath: String = System.getProperty("media.assets.path", "")
    val appBundleCachePath: File = File(System.getProperty("app.bundle.cache.path", System.getenv("HOME")), "app_bundle_cache")
    val appBundleCacheRemotePath: File = File(System.getProperty("app.bundle.cache.remote.path", "/Users/qa/app_bundle_cache"))
    val videoRecorderClassName = System.getProperty("video.recorder", SimulatorVideoRecorder::class.qualifiedName)
    val simulatorBackupPath: String? = System.getProperty("simulator.backup.path")
}
