package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.HostFactory
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.ISimulatorHostProvider
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class HostFactoryTest {
    companion object {
        const val WDA_BUNDLE_PATH_STRING = "/ABSOLUTE/PATH/ios/facebook/simulators/WebDriverAgentRunner-Runner.app"
        const val WDA_DEVICE_BUNDLE_PATH_STRING = "/ABSOLUTE/PATH/ios/facebook/devices/WebDriverAgentRunner-Runner.app"
    }
    init {
        System.setProperty("wda.bundle.path", WDA_BUNDLE_PATH_STRING)
        System.setProperty("wda.device.bundle.path", WDA_DEVICE_BUNDLE_PATH_STRING)
        System.setProperty("device.server.config.path", "/some/dir/some/file")
    }

    private val hostName: String = "some.node.name"

    private val deviceServerConfig = DeviceServerConfig(
            mapOf("" to "1"),
            listOf(NodeConfig(host = hostName, simulatorLimit = 99, concurrentBoots = 9))
    )

    private var remoteMock: IRemote = mockThis()
    private var remoteMockProvider: (String, String, String) -> IRemote = { host, _, _ ->
        assertThat(host, equalTo(hostName))
        remoteMock
    }

    private var simulatorHostProvider: ISimulatorHostProvider = mockThis()

    private var simNodeMock: ISimulatorsNode = mockThis()

    private var factory = HostFactory(remoteMockProvider, simulatorHostProvider)

    @Test
    fun getHostFromConfigHandlesReachableLocalDevice() {
        whenever(remoteMock.isReachable()).thenReturn(true)
        whenever(remoteMock.isLocalhost()).thenReturn(true)
        whenever(simulatorHostProvider.simulatorsNode(
                eq(remoteMock), eq(99), eq(9),
                any()
        )).thenReturn(simNodeMock)

        val nodeConfig = deviceServerConfig.nodes.first()
        val actual = factory.getHostFromConfig(nodeConfig)

        assertThat(actual, sameInstance(simNodeMock))
    }
}