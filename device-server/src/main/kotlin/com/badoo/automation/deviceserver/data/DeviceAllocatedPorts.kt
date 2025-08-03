package com.badoo.automation.deviceserver.data

import kotlin.reflect.full.memberProperties

data class DeviceAllocatedPorts(
    val fbsimctlPort: Int,
    val wdaPort: Int,
    val calabashPort: Int,
    val mjpegServerPort: Int,
) {
    fun toList(): List<Int> {
        return listOf(fbsimctlPort, wdaPort, calabashPort, mjpegServerPort)
    }

    companion object {
        fun portsCount(): Int {
            return DeviceAllocatedPorts::class.memberProperties.size
        }

        fun fromList(ports: List<Int>): DeviceAllocatedPorts {
            if (ports.size != portsCount()) {
                throw IllegalArgumentException("Expected ${portsCount()} ports, but got ${ports.size}")
            }
            return DeviceAllocatedPorts(
                fbsimctlPort = ports[0],
                wdaPort = ports[1],
                calabashPort = ports[2],
                mjpegServerPort = ports[3]
            )
        }
    }
}