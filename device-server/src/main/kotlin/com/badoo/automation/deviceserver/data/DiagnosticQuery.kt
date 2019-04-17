package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class DiagnosticQuery(
    @JsonProperty("process")
    val process: String? = null
)
