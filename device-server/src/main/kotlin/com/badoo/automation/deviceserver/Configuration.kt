package com.badoo.automation.deviceserver

object Configuration {

    private const val wda_bundle_path_property = "wda.bundle.path"
    private const val wda_device_bundle_path_property = "wda.device.bundle.path"
    val WDA_BUNDLE_PATH: String = System.getProperty(wda_bundle_path_property)
            ?: throw RuntimeException("Must set system property: -D$wda_bundle_path_property=" +
                    "/ABSOLUTE/PATH/ios/facebook/simulators/WebDriverAgentRunner-Runner.app")

    val WDA_DEVICE_BUNDLE_PATH: String = System.getProperty(wda_device_bundle_path_property)
            ?: throw RuntimeException("Must set system property: -D$wda_device_bundle_path_property=" +
                    "/ABSOLUTE/PATH/ios/facebook/devices/WebDriverAgentRunner-Runner.app")

    private const val device_server_config_path = "device.server.config.path"
    val DEVICE_SERVER_CONFIG_PATH: String = System.getProperty(device_server_config_path)
            ?: throw RuntimeException("Must set system property: -D$device_server_config_path=./config/.device_config")
}