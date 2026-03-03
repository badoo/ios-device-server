package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.device.ConfiguredDevice
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceServerConfig(
    val timeouts: Map<String, String> = emptyMap(),

    @JsonProperty("public_host")
    val publicHostName: String? = null,

    val simulators: SimulatorsConfig? = null,
    val devices: DevicesConfig? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimulatorsConfig(
    @JsonProperty("limit")
    val simulatorLimit: Int = 5,

    @JsonProperty("concurrent_boots")
    val concurrentBoots: Int = 1,

    @JsonProperty("disabled_services")
    val disabledSimulatorServices: List<String> = emptyList(),

    @JsonProperty("shutdown_simulators")
    val shutdownSimulators: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DevicesConfig(
    @JsonProperty("whitelist_apps")
    val whitelistApps: Set<String> = emptySet(),

    @JsonProperty("uninstall_apps")
    val uninstallApps: Boolean = false,

    @JsonProperty("configured")
    val configuredDevices: Set<ConfiguredDevice> = emptySet()
)
