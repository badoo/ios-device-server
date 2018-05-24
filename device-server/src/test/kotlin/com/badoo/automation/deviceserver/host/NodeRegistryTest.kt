package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.deviceDTOStub
import com.badoo.automation.deviceserver.host.management.NodeRegistry
import com.badoo.automation.deviceserver.host.management.NodeWrapper
import com.badoo.automation.deviceserver.ios.IActiveDevices
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.atLeast
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Before
import org.junit.Test
import java.time.Duration

class NodeRegistryTest {
    private val activeDevices: IActiveDevices = mockThis()
    private val nodeRegistry: NodeRegistry = NodeRegistry(activeDevices)
    private val headless = true
    private val desiredCapabilities = DesiredCapabilities("udid", "model", "os", headless)
    private val nodeWrapper1: NodeWrapper = mockThis("wrapper1")
    private val nodeWrapper2: NodeWrapper = mockThis("wrapper2")
    private val wrappedNode1: ISimulatorsNode = mockThis("node1")
    private val wrappedNode2: ISimulatorsNode = mockThis("node2")
    private val capacityNotBusy = 0.8F
    private val capacityBusy = 0.3F

    @Before
    fun setUp() {
        whenever(wrappedNode1.remoteAddress).thenReturn("n1")
        whenever(wrappedNode2.remoteAddress).thenReturn("n2")

        whenever(nodeWrapper1.node).thenReturn(wrappedNode1)
        whenever(nodeWrapper2.node).thenReturn(wrappedNode2)

        nodeRegistry.add(nodeWrapper1)
        nodeRegistry.add(nodeWrapper2)
    }

    @Test
    fun removeIfPresent() {
        nodeRegistry.removeIfPresent(nodeWrapper1)
        nodeRegistry.removeIfPresent(nodeWrapper1)

        assertThat(nodeRegistry.getAll().size, equalTo(1))

        verify(activeDevices, atLeast(1)).unregisterNodeDevices(wrappedNode1)
    }

    @Test
    fun capacityIgnoresDeadNodes() {
        // arrange
        whenever(wrappedNode1.totalCapacity(any())).thenReturn(1)
        whenever(wrappedNode2.totalCapacity(any())).thenReturn(1)

        whenever(nodeWrapper1.isAlive()).thenReturn(false)
        whenever(nodeWrapper2.isAlive()).thenReturn(true)

        // act
        val actual = nodeRegistry.capacitiesTotal(desiredCapabilities)

        // assert
        val expected = mapOf("total" to 1)
        assertThat(actual, equalTo(expected))
    }

    @Test
    fun createSimulatorByCapacity() {
        val deviceTimeout = Duration.ofSeconds(0)
        whenever(nodeWrapper1.isAlive()).thenReturn(true)
        whenever(nodeWrapper2.isAlive()).thenReturn(true)
        whenever(wrappedNode1.createDeviceAsync(desiredCapabilities)).then { deviceDTOStub("") }
        whenever(wrappedNode2.createDeviceAsync(desiredCapabilities)).then { deviceDTOStub("") }

        whenever(wrappedNode1.capacityRemaining(desiredCapabilities)).thenReturn(capacityBusy)
        whenever(wrappedNode2.capacityRemaining(desiredCapabilities)).thenReturn(capacityNotBusy)

        // act
        nodeRegistry.createDeviceAsync(desiredCapabilities, deviceTimeout, null)

        // assert
        verify(activeDevices).registerDevice("", wrappedNode2, deviceTimeout, null)
    }
}

