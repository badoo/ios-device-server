package com.badoo.automation.deviceserver

class ApplicationConfiguration {
    private val wdaSimulatorBundlePathProperty = "wda.bundle.path"
    val wdaSimulatorBundlePath: String = System.getProperty(wdaSimulatorBundlePathProperty)
            ?: throw RuntimeException("Must set system property: -D$wdaSimulatorBundlePathProperty=" +
                    "/ABSOLUTE/PATH/ios/facebook/simulators/WebDriverAgentRunner-Runner.app")

    private val wdaDeviceBundlePathProperty = "wda.device.bundle.path"
    val wdaDeviceBundlePath: String = System.getProperty(wdaDeviceBundlePathProperty)
            ?: throw RuntimeException("Must set system property: -D$wdaDeviceBundlePathProperty=" +
                    "/ABSOLUTE/PATH/ios/facebook/devices/WebDriverAgentRunner-Runner.app")

    private val deviceServerConfigPathProperty = "device.server.config.path"
    val deviceServerConfigPath: String = System.getProperty(deviceServerConfigPathProperty)
            ?: throw RuntimeException("Must set system property: -D$deviceServerConfigPathProperty=./config/.device_config")

    val fbsimctlVersion: String = System.getProperty("fbsimctl.version", "HEAD-d30c2a73")

    val remoteWdaSimulatorBundleRoot = System.getProperty("remote.wda.simulator.bundle.path", "/usr/local/opt/web_driver_agent_simulator")

    val remoteWdaDeviceBundleRoot = System.getProperty("remote.wda.device.bundle.path", "/usr/local/opt/web_driver_agent_device")

    val trustStorePath: String = System.getProperty("trust.store.path", "")
}