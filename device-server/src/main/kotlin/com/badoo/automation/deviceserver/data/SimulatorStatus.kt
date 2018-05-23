package com.badoo.automation.deviceserver.data

data class SimulatorStatus(
        var isReady: Boolean = false,
        var wdaStatus: Boolean = false,
        var fbsimctlStatus: Boolean = false,
        @Volatile var wdaStatusRetries: Int = 0,
        @Volatile var fbsimctlStatusRetries: Int = 0
)
