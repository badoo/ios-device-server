package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class FileDto(
    @JsonProperty("file_name")
    val file_name: String,

    @JsonProperty("data")
    val data: ByteArray
)
