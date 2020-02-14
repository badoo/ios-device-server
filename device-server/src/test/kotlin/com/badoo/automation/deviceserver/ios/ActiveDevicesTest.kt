package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.deviceDTOStub
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class ActiveDevicesTest {

    @Test
    fun deviceListShouldIgnoreDisconnectedDevices() {
        val activeDevices = ActiveDevices()
        val node = mockThis<ISimulatorsNode>()

        whenever(node.getDeviceDTO("ref1")).thenReturn(deviceDTOStub("ref1"))
        whenever(node.getDeviceDTO("ref2")).thenThrow(DeviceNotFoundException(""))
        whenever(node.getDeviceDTO("ref3")).thenReturn(deviceDTOStub("ref3"))

        activeDevices.registerDevice("ref1", node, null)
        activeDevices.registerDevice("ref2", node, null)
        activeDevices.registerDevice("ref3", node, null)

        val list = activeDevices.deviceList()

        assertEquals(listOf("ref1", "ref3"), list.map { it.ref }.sorted())
        assertEquals(2, activeDevices.deviceRefs().size)
    }
}
