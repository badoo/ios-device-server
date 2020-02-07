package com.badoo.automation.deviceserver.data

data class DeviceAllocatedPorts(
    val fbsimctlPort: Int,
    val wdaPort: Int,
    val calabashPort: Int,
    val mjpegServerPort: Int,
    private val defaultCalabashPort: Int = 37265
) {
    fun toSet(): Set<Int> {
        return setOf(fbsimctlPort, wdaPort, calabashPort, mjpegServerPort, defaultCalabashPort)
    }
}
