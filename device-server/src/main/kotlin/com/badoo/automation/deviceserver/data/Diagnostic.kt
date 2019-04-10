package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class Diagnostic(
    @JsonProperty("type")
    val type: DiagnosticType,

    @JsonProperty("content")
    val content: String
)
