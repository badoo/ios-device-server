package com.badoo.automation.deviceserver.data

data class DeviceAllocatedPorts(
    val fbsimctlPort: Int,
    val wdaPort: Int,
    val calabashPort: Int,
    val mjpegServerPort: Int,
) {
    fun toSet(): Set<Int> {
        return setOf(fbsimctlPort, wdaPort, calabashPort, mjpegServerPort)
    }
}