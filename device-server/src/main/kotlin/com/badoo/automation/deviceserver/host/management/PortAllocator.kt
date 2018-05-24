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
        val take = allocate(3)
        return DeviceAllocatedPorts(take[0], take[1], take[2])
    }

    fun deallocateDAP(dap: DeviceAllocatedPorts) {
        deallocate(listOf(dap.calabashPort, dap.fbsimctlPort, dap.wdaPort))
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