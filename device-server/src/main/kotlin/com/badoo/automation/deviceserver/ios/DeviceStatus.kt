package com.badoo.automation.deviceserver.ios

data class DeviceStatus (
    val ready: Boolean,
    val wda_status: Boolean,
    val fbsimctl_status: Boolean,
    val state: String,
    val last_error: Exception?
)