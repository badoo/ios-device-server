package com.badoo.automation.deviceserver.data

data class DeviceAllocatedPorts(
    val wdaPort: Int,
    val calabashPort: Int,
    val mjpegServerPort: Int,
) {
    fun toSet(): Set<Int> {
        return setOf(wdaPort, calabashPort, mjpegServerPort)
    }
}