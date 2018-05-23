package com.badoo.automation.deviceserver.ios.fbsimctl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class FBSimctlDevice(
        val arch: String = "",
        val state: String,
        val model: String = "",
        val name: String = "",
        val udid: String,
        val os: String = ""
)

data class FBSimctlAppInfo(
        val data_container: String?,
        val bundle: FBSimctlAppInfoBundle,
        val install_type: String?
)

data class FBSimctlAppInfoBundle(
        val path: String?,
        val bundle_id: String,
        val binary: Map<String, Any?>?,
        val name: String?
)

enum class FBSimctlDeviceState(val value: String) {
    BOOTED("Booted"),
    SHUTDOWN("Shutdown")
}

data class FBSimctlDeviceDiagnosticInfo(
        val sysLogLocation: String?,
        val coreSimulatorLogLocation: String?,
        val videoLocation: String?
)