package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts

class PortAllocator(min: Int = PORT_RANGE_START, max: Int = PORT_RANGE_END) {
    companion object {
        // this port range seems to be free and not in conflict with zabbix, etc.
        const val PORT_RANGE_START = 41798
        const val PORT_RANGE_END = 42507
    }

    private val ports: MutableSet<Int> = IntRange(min, max).toMutableSet()

    fun allocateDAP(): DeviceAllocatedPorts {
        val take = allocate(5)
        return DeviceAllocatedPorts(take[0], take[1], take[2], take[3], take[4])
    }

    fun deallocateDAP(allocatedPorts: DeviceAllocatedPorts) {
        synchronized(this) {
            ports.addAll(allocatedPorts.toSet())
        }
    }

    fun available(): Int {
        return ports.size
    }

    private fun allocate(entries: Int): List<Int> {
        //TODO: Check with "netstat -nat|grep LISTEN|grep -v tcp6" if ports are already occupied
        synchronized(this) {
            if (ports.size < entries) {
                throw RuntimeException("No more ports to allocate")
            }
            val takenPorts = ports.take(entries)
            ports.removeAll(takenPorts)
            return takenPorts
        }
    }
}