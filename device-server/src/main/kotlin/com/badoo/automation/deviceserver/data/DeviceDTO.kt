package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI

data class DeviceDTO(
        val ref: DeviceRef,
        val state: DeviceState,
        val fbsimctl_endpoint: URI,
        val wda_endpoint: URI,
        val calabash_port: Int,
        val calabash_endpoint: URI,
        val mjpeg_server_port: Int,
        val appium_port: Int,
        val appium_endpoint: URI,
        val info: DeviceInfo,
        val last_error: ErrorDto?,
        val capabilities: ActualCapabilities?
)

data class ActualCapabilities(
        @JsonProperty("set_location")
        val setLocation: Boolean,

        @JsonProperty("terminate_app")
        val terminateApp: Boolean,

        @JsonProperty("remote_notifications")
        val remoteNotifications: Boolean,

        @JsonProperty("appium_enabled")
        val isAppiumEnabled: Boolean,

        @JsonProperty("video_capture")
        val videoCapture: Boolean
)