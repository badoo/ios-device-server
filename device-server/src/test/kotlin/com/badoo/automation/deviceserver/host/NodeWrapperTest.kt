package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.NodeRegistry
import com.badoo.automation.deviceserver.host.management.NodeWrapper
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.*
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeWrapperTest {
    private val registry: NodeRegistry = mockThis()
    private val hostFactory: IHostFactory = mockThis()
    private val nodeMock: IDeviceNode = mockThis()
    private val config = NodeConfig("user", "localhost")

    @Test
    fun startStopPeriodicHealthChecks() {
        val nodeCheckInterval = 100L
        whenever(hostFactory.getHostFromConfig(any())).thenReturn(nodeMock)
        whenever(nodeMock.isReachable()).thenReturn(true)

        val nodeWrapper = Mockito.spy(getWrapperWithMocks(nodeCheckInterval))
        nodeWrapper.start()
        nodeWrapper.startPeriodicHealthCheck()
        Thread.sleep(nodeCheckInterval + nodeCheckInterval/2) // to guarantee a call to isAlive
        nodeWrapper.stop()
        Thread.sleep(nodeCheckInterval * 2) // to ensure that healthChecking thread is not terminated by jvm, but by the nodeWrapper

        verify(nodeMock, times(2)).isReachable()
    }

    @Ignore
    @Test
    fun failsToInitOnFactoryError() {
        whenever(hostFactory.getHostFromConfig(any())).thenThrow(RuntimeException())
        assertFailsWith<RuntimeException> {
            getWrapperWithMocks()
        }
    }

    @Test
    fun failsToStartIfUnreachable() {
        whenever(hostFactory.getHostFromConfig(any())).thenReturn(nodeMock)
        whenever(nodeMock.isReachable()).thenReturn(false)

        val started = getWrapperWithMocks().start()

        assertFalse(started)
    }

    @Test
    fun failsToStartIfStarted() {
        whenever(hostFactory.getHostFromConfig(any())).thenReturn(nodeMock)
        whenever(nodeMock.isReachable()).thenReturn(true)
        val nodeWrapper = getWrapperWithMocks()

        val startedFirst = nodeWrapper.start()
        val startedSecond = nodeWrapper.start()

        assertTrue(startedFirst)
        assertFalse(startedSecond)
    }

    @Test
    fun unregistersSelfIfUnreachableLongEnough() {
        whenever(hostFactory.getHostFromConfig(any())).thenReturn(nodeMock)
        whenever(nodeMock.isReachable()).thenReturn(true, false, false, false, false)
        val nodeWrapper = getWrapperWithMocks()
        nodeWrapper.start()
        nodeWrapper.startPeriodicHealthCheck()

        Thread.sleep(1000)
        verify(registry).removeIfPresent(nodeWrapper)
    }

    private fun getWrapperWithMocks() = getWrapperWithMocks(1)

    private fun getWrapperWithMocks(nodeCheckInterval: Long) =
        NodeWrapper(config, hostFactory, registry, 2, Duration.ofMillis(nodeCheckInterval))
}
