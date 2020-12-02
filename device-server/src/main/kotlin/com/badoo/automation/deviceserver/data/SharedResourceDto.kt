package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class SharedResourceDto(
    @JsonProperty("data")
    val data: ByteArray,

    @JsonProperty("path")
    val path: String
)
