package com.badoo.automation.deviceserver.data

import java.net.URI

data class DeviceDTO(
        val ref: DeviceRef,
        val state: DeviceState,
        val fbsimctl_endpoint: URI,
        val wda_endpoint: URI,
        val calabash_port: Int,
        val user_ports: Set<Int>, // From PortAllocator
        val info: DeviceInfo,
        val last_error: ErrorDto?,
        val capabilities: ActualCapabilities?
)

data class ActualCapabilities(
        val setLocation: Boolean,
        val terminateApp: Boolean
)