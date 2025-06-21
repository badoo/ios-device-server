package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class SimulatorStatusDTO(
    @JsonProperty("ready")
    val ready: Boolean,

    @JsonProperty("wda_status")
    val wdaStatus: Boolean,

    @JsonProperty("state")
    val state: String,

    @JsonProperty("last_error")
    val lastError: ExceptionDTO?
)

data class ExceptionDTO(val type: String, val message: String, val stackTrace: List<String>)

fun Exception.toDTO(): ExceptionDTO {
    return ExceptionDTO(
        type = this.javaClass.name,
        message = this.message ?: "",
        stackTrace = stackTrace.map { it.toString() }
    )
}
