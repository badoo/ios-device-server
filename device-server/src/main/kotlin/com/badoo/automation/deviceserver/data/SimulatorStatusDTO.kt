package com.badoo.automation.deviceserver.data

data class SimulatorStatusDTO (
        val ready: Boolean,
        val wda_status: Boolean,
        val fbsimctl_status: Boolean,
        val state: String,
        val last_error: ExceptionDTO?
)

data class ExceptionDTO(val type: String, val message: String, val stackTrace: List<String>)