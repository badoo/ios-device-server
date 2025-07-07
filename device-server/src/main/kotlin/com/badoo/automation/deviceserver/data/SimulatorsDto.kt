package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.simctl.models.Simulator

data class SimulatorsDto(
    val baseSimulators: Set<Simulator>,
    val clonedSimulators: Set<Simulator>,
    val allSimulators: Set<Simulator>
)
