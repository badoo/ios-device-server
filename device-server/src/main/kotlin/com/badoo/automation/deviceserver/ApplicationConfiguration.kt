package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.simulator.video.FFMPEGVideoRecorder
import java.io.File
import java.lang.Boolean
import java.lang.IllegalStateException

class ApplicationConfiguration {
    val homeDirectory = File(System.getProperty("user.home"))
    val wdaDeviceBundles: String = System.getProperty("wda.device.bundles")
        ?: throw RuntimeException("Must set system property: -Dwda.device.bundles=/ABSOLUTE/PATH/ios/facebook/devices/,")

    val wdaSimulatorBundles: String = System.getProperty("wda.simulator.bundles")
        ?: throw RuntimeException("Must set system property: -Dwda.simulator.bundles=/ABSOLUTE/PATH/ios/facebook/simulator/")

    private val deviceServerConfigPathProperty = "device.server.config.path"
    val deviceServerConfigPath: String = System.getProperty(deviceServerConfigPathProperty)
        ?: throw RuntimeException("Must set system property: -D$deviceServerConfigPathProperty=./config/.device_config")

    val remoteWdaSimulatorBundleRoot: String = System.getProperty("remote.wda.simulator.bundle.path", "/usr/local/opt/web_driver_agent_simulator")
    val remoteWdaDeviceBundleRoot: String = System.getProperty("remote.wda.device.bundle.path", "/usr/local/opt/web_driver_agent_device")
    val remoteTestHelperAppBundleRoot: String = System.getProperty("remote.test.helper.app.bundle.path", "/usr/local/opt/ios-device-server/test_helper_app")
    val useTestHelperApp = Boolean.getBoolean("useTestHelperApp")
    val tempFolder = File(System.getProperty("java.io.tmpdir") ?: throw IllegalStateException("Property java.io.tmpdir is not defined"))
    val trustStorePath: String = System.getProperty("trust.store.path", "")
    val assetsPath: String = System.getProperty("media.assets.path", "")
    val appBundleCachePath: File = File(System.getProperty("app.bundle.cache.path", File(homeDirectory, ".iosctl/app_bundle_cache").absolutePath))
    val videoRecorderClassName: String = System.getProperty("video.recorder", FFMPEGVideoRecorder::class.qualifiedName)
    val simulatorBackupPath: String = System.getProperty("simulator.backup.path", File(homeDirectory, ".iosctl/ios_simulator_backups").absolutePath)
}
