package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class PlistEntryDTO(
    @JsonProperty("bundle_id")
    val bundleId: String,

    @JsonProperty("file_name")
    val file_name: String,

    @JsonProperty("property_name")
    val key: String,

    @JsonProperty("property_value")
    val value: String,

    @JsonProperty("property_type")
    val type: String?,

    @JsonProperty("command") // set / add
    val command: String
)