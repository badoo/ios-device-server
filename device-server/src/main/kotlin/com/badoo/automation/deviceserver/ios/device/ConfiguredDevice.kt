package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.UDID
import com.fasterxml.jackson.annotation.JsonProperty

data class ConfiguredDevice(
    @JsonProperty("udid")
    val udid: UDID
)