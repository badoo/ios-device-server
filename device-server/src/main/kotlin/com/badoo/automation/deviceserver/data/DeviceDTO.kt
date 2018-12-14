package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty
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
        @JsonProperty("set_location")
        val setLocation: Boolean,

        @JsonProperty("terminate_app")
        val terminateApp: Boolean,

        @JsonProperty("video_capture")
        val videoCapture: Boolean
)