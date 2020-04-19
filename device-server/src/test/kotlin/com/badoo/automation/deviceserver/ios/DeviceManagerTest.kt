package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.DeviceServerConfig
import com.badoo.automation.deviceserver.NodeConfig
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.deviceDTOStub
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.host.management.DeviceManager
import com.badoo.automation.deviceserver.host.management.IHostFactory
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.sameInstance
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.net.URL

class DeviceManagerTest {
    private var desiredCaps = DesiredCapabilities("udid", "model", "os", true)

    private var someUrl = URL("http://whatever")

    private val ref: DeviceRef = "just-a-device-ref"
    private val expectedDto = deviceDTOStub("mockDTO")
    private val bundleId = "some.bundle.id"
    private val hostZero = mockHostWithTotalCapacity(0, true)
    private val hostOne = mockHostWithTotalCapacity(1, true)
    private val hostTwo = mockHostWithTotalCapacity(2, true)

    private val hostsMap = mapOf(
            "zero" to hostZero, "one" to hostOne, "two" to hostTwo,
            "unreachable" to mockHostWithTotalCapacity(4, false)
    )
    private val hostFactory: IHostFactory = object : IHostFactory {
        override fun getHostFromConfig(config: NodeConfig): ISimulatorsNode {
            val nodeName = config.host
            return hostsMap[nodeName]!!
        }
    }

    private fun mockHostWithTotalCapacity(total: Int, reachable: Boolean): ISimulatorsNode {
        val m: ISimulatorsNode = Mockito.mock(ISimulatorsNode::class.java, "mockHost$total")
        whenever(m.totalCapacity(desiredCaps)).thenReturn(total)
        whenever(m.isReachable()).thenReturn(reachable)
        return m
    }

    private val activeDevices: ActiveDevices = mockThis()

    private val deviceManager = DeviceManager(
            DeviceServerConfig(
                    emptyMap(),
                    setOf()
            ),
            hostFactory,
            activeDevices
    )

    @Test
    fun getDeviceRefsEmpty() {
        hostsMap.forEach { _, mock -> whenever(mock.list()).thenReturn(emptyList()) }

        val actualRefs = deviceManager.getDeviceRefs() // .list()

        assertThat(actualRefs.size, equalTo(0))
    }

    @Test
    fun deleteReleaseDeviceThatHasBeenReleased() {
        val sessionId = "defaultSessionId"
        whenever(activeDevices.getNodeFor(ref)).thenThrow(
                DeviceNotFoundException("Device [$ref] not found in [$sessionId] activeDevices")
        )
        deviceManager.deleteReleaseDevice(ref, "httpRequest")
        verify(activeDevices, times(0)).unregisterDeleteDevice(any())
    }

    private fun withDeviceOnHost(host: ISimulatorsNode, block: () -> Unit) {
        whenever(activeDevices.getNodeFor(ref)).thenReturn(host)
        block()
    }

    @Test
    fun getMethodReturningDeviceDTO() {
        withDeviceOnHost(hostTwo ) {
        whenever(hostTwo.getDeviceDTO(ref)).thenReturn(expectedDto)

        val actualDto = deviceManager.getGetDeviceDTO(ref)

        assertThat(actualDto, equalTo(expectedDto))
    }
    }

    @Test
    fun clearSafariCookies() {
        withDeviceOnHost(hostTwo ) {
        deviceManager.clearSafariCookies(ref)
        verify(hostTwo).clearSafariCookies(ref)
    }
    }

    @Test
    fun resetAsyncDevice() {
        withDeviceOnHost(hostTwo ) {

        deviceManager.resetAsyncDevice(ref)

        verify(hostTwo).resetAsync(ref)
    }
    }

    @Test
    fun getEndpointFor() {
        withDeviceOnHost(hostTwo ) {
        whenever(hostTwo.endpointFor(ref, 1234)).thenReturn(someUrl)
        val actual = deviceManager.getEndpointFor(ref, 1234)
        assertThat(actual, equalTo(someUrl))
    }
    }

    @Test
    fun getLastCrashLog() {
        withDeviceOnHost(hostTwo) {
            val crashLog = CrashLog("some/path", "stdout from cat of filename")
            whenever(hostTwo.lastCrashLog(ref)).thenReturn(crashLog)
            val actual = deviceManager.getLastCrashLog(ref)
            assertThat(actual, equalTo(crashLog))
        }
    }

    @Test
    fun startVideo() {
        withDeviceOnHost(hostTwo) {
            deviceManager.startVideo(ref)
            verify(hostTwo).videoRecordingStart(ref)
        }
    }

    @Test
    fun stopVideo() {
        withDeviceOnHost(hostTwo) {
            deviceManager.stopVideo(ref)
            verify(hostTwo).videoRecordingStop(ref)
        }
    }

    @Test
    fun getVideo() {
        withDeviceOnHost(hostTwo) {
            val bytes = ByteArray(3)
            whenever(hostTwo.videoRecordingGet(ref)).thenReturn(bytes)
            val actual = deviceManager.getVideo(ref)
            assertThat(actual, sameInstance(bytes))
        }
    }

    @Test
    fun deleteVideo() {
        withDeviceOnHost(hostTwo) {
            deviceManager.deleteVideo(ref)
            verify(hostTwo).videoRecordingDelete(ref)
        }
    }

    @Test
    fun getDeviceState() { // deviceStateDTO
        withDeviceOnHost(hostTwo ) {
        val deviceOrSimulatorStatusBloodyContradictoryNonsense = SimulatorStatusDTO(
            false, false, false, DeviceState.NONE.value, null)
        whenever(hostTwo.state(ref)).thenReturn(deviceOrSimulatorStatusBloodyContradictoryNonsense)
        val actual = deviceManager.getDeviceState(ref)
        assertThat(actual, equalTo(deviceOrSimulatorStatusBloodyContradictoryNonsense))
    }
    }

    @Test
    fun setEnvironmentVariables() {
        withDeviceOnHost(hostTwo) {
            deviceManager.setEnvironmentVariables(ref, mapOf())
            verify(hostTwo).setEnvironmentVariables(ref, mapOf())
        }
    }
}
