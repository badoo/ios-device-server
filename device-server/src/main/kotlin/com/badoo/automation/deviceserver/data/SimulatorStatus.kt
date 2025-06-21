package com.badoo.automation.deviceserver.data

data class SimulatorStatus(
        var wdaStatus: Boolean = false,
        @Volatile var wdaStatusRetries: Int = 0,
        @Volatile var fbsimctlStatusRetries: Int = 0
) {
    val isReady: Boolean get() = wdaStatus
}
