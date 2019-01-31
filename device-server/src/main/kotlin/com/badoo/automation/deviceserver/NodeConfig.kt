package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.ios.device.KnownDevice
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties( ignoreUnknown = true )
data class NodeConfig(
    @JsonProperty("user")
    val user: String = "",

    @JsonProperty("host")
    val host: String = "localhost",

    @JsonProperty("public_host")
    val publicHost: String = host,

    @JsonProperty("simulator_limit")
    val simulatorLimit: Int = 6,

    @JsonProperty("concurrent_boots")
    val concurrentBoots: Int = 3,

    @JsonProperty("type")
    val type: NodeConfig.NodeType = NodeType.Simulators,

    @JsonProperty("whitelist_apps")
    val whitelistApps: Set<String> = emptySet(),

    @JsonProperty("uninstall_apps")
    val uninstallApps: Boolean = false,

    @JsonProperty("devices")
    val knownDevices: List<KnownDevice> = emptyList(),

    @JsonProperty("environment_variables")
    val environmentVariables: Map<String, String> = mapOf()
) {

    enum class NodeType {
        @JsonProperty("simulators")
        Simulators,

        @JsonProperty("devices")
        Devices
    }
}
