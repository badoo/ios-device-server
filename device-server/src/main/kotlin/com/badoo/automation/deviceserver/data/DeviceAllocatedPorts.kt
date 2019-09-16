package com.badoo.automation.deviceserver.data

data class DeviceAllocatedPorts(
    val fbsimctlPort: Int,
    val wdaPort: Int,
    val calabashPort: Int,
    val videoPort: Int,
    private val defaultCalabashPort: Int = 37265
) {
    fun toSet(): Set<Int> {
        return setOf(fbsimctlPort, wdaPort, calabashPort, defaultCalabashPort)
    }
}