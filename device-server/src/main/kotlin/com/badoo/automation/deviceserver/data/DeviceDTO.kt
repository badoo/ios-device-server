package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI

data class DeviceDTO(
        val ref: DeviceRef,
        val state: DeviceState,
        val wda_endpoint: URI,
        val calabash_port: Int,
        val calabash_endpoint: URI,
        val mjpeg_server_port: Int,
        val info: DeviceInfo,
        val last_error: ErrorDto?,
        val capabilities: ActualCapabilities?
) {
        constructor(simulator: ISimulator) : this(
                ref = simulator.ref,
                state = simulator.deviceState,
                wda_endpoint = simulator.wdaEndpoint,
                calabash_port = simulator.calabashPort,
                calabash_endpoint = simulator.calabashEndpoint,
                mjpeg_server_port = simulator.mjpegServerPort,
                info = simulator.deviceInfo,
                last_error = simulator.lastException?.toDto(),
                capabilities = ActualCapabilities(
                        setLocation = true,
                        terminateApp = true,
                        remoteNotifications = simulator.deviceInfo.isRemoteNotificationsSupported,
                        videoCapture = true
                )
        )
}

data class ActualCapabilities(
        @JsonProperty("set_location")
        val setLocation: Boolean,

        @JsonProperty("terminate_app")
        val terminateApp: Boolean,

        @JsonProperty("remote_notifications")
        val remoteNotifications: Boolean,

        @JsonProperty("video_capture")
        val videoCapture: Boolean
)