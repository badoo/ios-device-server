package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.ios.simulator.Simulator

data class SimulatorStatusDTO (
        val ready: Boolean,
        val wda_status: Boolean,
        val appium_status: Boolean,
        val fbsimctl_status: Boolean,
        val state: String,
        val last_error: ExceptionDTO?,
        val simulator_services: Set<Simulator.RequiredService> = mutableSetOf()
)

data class ExceptionDTO(val type: String, val message: String, val stackTrace: List<String>)

fun Exception.toDTO(): ExceptionDTO {

        return ExceptionDTO(
                type = this.javaClass.name,
                message = this.message ?: "",
                stackTrace = stackTrace.map { it.toString() }
        )
}
