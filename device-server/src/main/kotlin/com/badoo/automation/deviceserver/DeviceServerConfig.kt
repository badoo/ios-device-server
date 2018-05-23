package com.badoo.automation.deviceserver

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties( ignoreUnknown = true )
data class DeviceServerConfig(
    val timeouts: Map<String, String>,
    val nodes: List<NodeConfig>
)
