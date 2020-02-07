package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts

class PortAllocator(min: Int = PORT_RANGE_START, max: Int = PORT_RANGE_END) {
    companion object {
        // this port range seems to be free and not in conflict with zabbix, etc.
        const val PORT_RANGE_START = 41798
        const val PORT_RANGE_END = 42507
    }

    private var ports: Set<Int> = IntRange(min, max).toSet()

    fun allocateDAP(): DeviceAllocatedPorts {
        val ports = allocate(4)
        return DeviceAllocatedPorts(ports[0], ports[1], ports[2], ports[3])
    }

    fun deallocateDAP(dap: DeviceAllocatedPorts) {
        deallocate(listOf(dap.calabashPort, dap.fbsimctlPort, dap.wdaPort, dap.mjpegServerPort))
    }

    fun available(): Int {
        return ports.size
    }

    private fun allocate(entries: Int): List<Int> {
        synchronized(this) {
            if (ports.size < entries) {
                throw RuntimeException("No more ports to allocate")
            }
            val take = ports.take(entries)
            ports = ports.subtract(take)
            return take
        }
    }

    private fun deallocate(entries: List<Int>) {
        synchronized(this) {
            ports = ports.plus(entries)
        }
    }
}
