package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.UDID
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(value = ["wifi_address"])
data class KnownDevice(
    @JsonProperty("udid")
    val udid: UDID,

    @JsonProperty("ip")
    val ipAddress: String
)