package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.JsonMapper
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.management.ISimulatorHostChecker
import com.badoo.automation.deviceserver.host.management.PortAllocator
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.ios.simulator.ISimulator
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.*
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI

class SimulatorsNodeTest {
    private val iRemote: IRemote = mockThis()
    private val fbSimctl: FBSimctl = mockThis()

    init {
        whenever(iRemote.fbsimctl).thenReturn(fbSimctl)
    }

    private val hostChecker: ISimulatorHostChecker = mockThis()

    private val wdaPath = File("some/file/from/wdaPathProc")

    private val iSimulatorProvider: ISimulatorProvider = mockThis()

    private val ref1: DeviceRef = "Udid1-rem-ote-node"

    private val fbsimulatorDevice: FBSimctlDevice = FBSimctlDevice(
            "Arch",
            "State",
            "Model",
            "Name",
            "Udid1",
            "Os")

    private val fbsimulatorDevice2: FBSimctlDevice = FBSimctlDevice(
            "Arch",
            "State",
            "Model",
            "Name",
            "Udid2",
            "Os")

    private val portAllocator = PortAllocator(1, 10)

    private val configuredSimulatorLimit = 3

    private val simulatorFactory: ISimulatorFactory = mockThis()
    private val simulatorsNode1 = SimulatorsNode(
            iRemote,
            hostChecker,
            configuredSimulatorLimit,
            2,
            wdaPath,
            iSimulatorProvider,
            portAllocator,
            simulatorFactory
    )
    private val simulatorsNode = simulatorsNode1

    private val desiredCapabilities: DesiredCapabilities = mockThis()

    private val simulatorMock: ISimulator = mockThis("sim1")
    private val simulatorMock2: ISimulator = mockThis("sim2")

    private val expectedDeviceDTO = DeviceDTO(
            "someref0",
            DeviceState.CREATING,
            URI("http://fbsimctl"),
            URI("http://wda"),
            4444,
            setOf(1, 2, 3, 37265),
            DeviceInfo("", "", "", "", ""),
            null,
            ActualCapabilities(true, true)
    )
    private val expectedDeviceDTOJson = JsonMapper().toJson(expectedDeviceDTO)

    @Test
    fun shouldPrepareNodeOnlyOnce() {
        simulatorsNode1.prepareNode()

        val inOrder = inOrder(hostChecker)
        inOrder.verify(hostChecker).checkPrerequisites()
        inOrder.verify(hostChecker).copyWdaBundleToHost()
        inOrder.verify(hostChecker).cleanup()
        inOrder.verify(hostChecker).setupHost()
        inOrder.verifyNoMoreInteractions()
    }

    @Test(expected = RuntimeException::class)
    fun createDeviceAsyncFailsIfNoCapacity() {
        simulatorsNode.createDeviceAsync(desiredCapabilities)
    }

    @Test(expected = RuntimeException::class)
    fun createDeviceAsyncFailsIfNoMatch() {
        whenever(iSimulatorProvider.match(desiredCapabilities, emptySet())).thenReturn(null)
        simulatorsNode.createDeviceAsync(desiredCapabilities)
    }

    @Test
    fun createDeviceAsyncSucceeds() {
        createDeviceForTest()

        verify(simulatorFactory).newSimulator(
                eq("Udid1-rem-ote-node"),
                eq(iRemote),
                eq(fbsimulatorDevice),
                eq(DeviceAllocatedPorts(1,2, 3)),
                eq("/node/specific/device/set"),
                eq(File("some/file/from/wdaPathProc")),
                any(),
                eq(false),
                eq("FBSimctlDevice(arch=Arch, state=State, model=Model, name=Name, udid=Udid1, os=Os)"),
                eq(false)
        )
        verify(simulatorMock).prepareAsync()

        assertThat(simulatorsNode.count(), equalTo(1))
    }

    private fun createDeviceForTest(): DeviceDTO =
            createDevicesForTest(simulatorMock to fbsimulatorDevice)

    private fun createTwoDevicesForTest(): DeviceDTO =
            createDevicesForTest(simulatorMock to fbsimulatorDevice, simulatorMock2 to fbsimulatorDevice2)

    private fun createDevicesForTest(vararg simulatorMocks: Pair<ISimulator, FBSimctlDevice>): DeviceDTO {
        mockForSimulatorMocks(*simulatorMocks)
        return simulatorMocks.map {
            simulatorsNode.createDeviceAsync(desiredCapabilities)
        }.last()
    }

    private fun mockForSimulatorMocks(vararg simulatorMocks: Pair<ISimulator, FBSimctlDevice>) {
        whenever(iRemote.hostName).thenReturn("rem.ote.node")
        whenever(iRemote.publicHostName).thenReturn("rem.ote.node")
        whenever(iRemote.fbsimctl).thenReturn(fbSimctl)
        whenever(fbSimctl.defaultDeviceSet()).thenReturn("/node/specific/device/set")
        var fbsimmock = whenever(iSimulatorProvider.match(eq(desiredCapabilities), any()))
        simulatorMocks.forEach { pair ->
            fbsimmock = fbsimmock.thenReturn(pair.second)
        }

        var simfac = whenever(simulatorFactory.newSimulator(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        simulatorMocks.forEach { pair ->
            simfac = simfac.thenReturn(pair.first)
        }

        simulatorMocks.forEachIndexed { index, pair ->
            val it = pair.first
            whenever(it.ref).thenReturn("someref$index")
            whenever(it.state).thenReturn(DeviceState.CREATING)
            whenever(it.info).thenReturn(DeviceInfo("","","","",""))
            whenever(it.userPorts).thenReturn(DeviceAllocatedPorts(1,2,3))
            whenever(it.fbsimctlEndpoint).thenReturn(URI("http://fbsimctl"))
            whenever(it.wdaEndpoint).thenReturn(URI("http://wda"))
            whenever(it.calabashPort).thenReturn(4444 + index)
            whenever(it.fbsimctlSubject).thenReturn("string representation of simulatorMock $index")
        }
    }


    @Test
    fun countStartsAtZero() {
        assertThat(simulatorsNode.count(), equalTo(0))
    }

    @Test
    fun disposeReportsNoErrorsOnSuccessfulDispose() {
        createDeviceForTest()

        simulatorsNode.dispose()

        verify(hostChecker).killDiskCleanupThread()
        verify(simulatorMock).release(any())
    }

    @Test
    fun disposeReportsErrorsOnFailedDispose() {
        createDeviceForTest()

        whenever(simulatorMock.release(any())).thenThrow(RuntimeException("Oh no! Actually, this is an expected test exception"))

        simulatorsNode.dispose()

        verify(hostChecker).killDiskCleanupThread()
    }

    @Test
    fun list() {
        createTwoDevicesForTest()

        assertThat(simulatorsNode.list().count(), equalTo(2))
    }

    private val headless = true
    @Test
    fun supportsOnlyWhenArchIsx8664() {
        assertTrue(simulatorsNode.supports(DesiredCapabilities("", "", "", headless, arch = "x86_64")))
        assertFalse(simulatorsNode.supports(DesiredCapabilities("", "", "", headless, arch ="notx86_64")))
    }

    @Test
    fun totalCapacityReturnsNonzeroWhenSupported() {
        assertThat(simulatorsNode.totalCapacity(DesiredCapabilities("not", "des", "", headless)),
                equalTo(configuredSimulatorLimit))
    }

    @Test
    fun totalCapacityReturnsZeroWhenUnsupported() {
        assertThat(simulatorsNode.totalCapacity(DesiredCapabilities("not", "des", "ir", headless, arch = "ed")), equalTo(0))
    }

    @Test
    fun capacityRemainingReturns1WhenNothingIsAllocated() {
        assertThat(simulatorsNode.capacityRemaining(desiredCapabilities), equalTo(1F))

        createDeviceForTest()

        assertThat(simulatorsNode.capacityRemaining(desiredCapabilities), equalTo(2F/3))
    }

    @Test
    fun approveAccess() {
        createDeviceForTest()

        val bundleId = "somebundle"

        simulatorsNode.approveAccess(ref1, bundleId)

        verify(simulatorMock).approveAccess(bundleId)
    }

    @Test
    fun clearSafariCookies() {
        createDeviceForTest()

        simulatorsNode.clearSafariCookies(ref1)

        verify(simulatorMock).clearSafariCookies()
    }

    @Test
    fun endpointFor() {
        createDeviceForTest()

        simulatorsNode.endpointFor(ref1, 3)

        verify(simulatorMock).endpointFor(3)
    }

    @Test
    fun getDeviceDTO() {
        createDeviceForTest()

        val actual = simulatorsNode.getDeviceDTO(ref1)

        assertThat(actual, equalTo(DeviceDTO(
                "someref0",
                DeviceState.CREATING,
                URI("http://fbsimctl"),
                URI("http://wda"),
                4444,
                setOf(1,2,3, 37265),
                DeviceInfo("", "", "", "", ""),
                null,
                ActualCapabilities(true, true)
        )))
    }

    @Test
    fun getDeviceDTOJSON() {
        createDeviceForTest()

        val actual = simulatorsNode.getDeviceDTO(ref1)
        val actualJson = JsonMapper().toJson(actual)

        assertThat(actualJson, equalTo(expectedDeviceDTOJson))
    }

    @Test
    fun lastCrashLog() {
        createDeviceForTest()

        simulatorsNode.lastCrashLog(ref1)

        verify(simulatorMock).lastCrashLog()
    }

    @Test
    fun deleteReleaseIgnoresNonexistentRef() {
        assertThat(simulatorsNode.deleteRelease(ref1, "anything"), equalTo(false))
    }

    @Test
    fun deleteReleaseReleasesExistingRef() {
        createTwoDevicesForTest()
        assertThat(simulatorsNode.count(), equalTo(2))
        assertThat(portAllocator.available(), equalTo(4))

        val actual = simulatorsNode.deleteRelease(ref1, "test")
        assertThat(actual, equalTo(true))
        verify(simulatorMock).release(any())
        assertThat(simulatorsNode.count(), equalTo(1))
        assertThat(portAllocator.available(), equalTo(7))
    }

    @Test
    fun resetAsync() {
        createDeviceForTest()

        simulatorsNode.resetAsync(ref1)

        verify(simulatorMock).resetAsync()
    }

    @Test
    fun state() {
        createDeviceForTest()
        val expected = SimulatorStatusDTO(false, false, false, DeviceState.CREATING.value, null)

        whenever(simulatorMock.status()).thenReturn(expected)

        val actual = simulatorsNode.state(ref1)

        assertThat(actual, sameInstance(expected))

        verify(simulatorMock).status()
    }

    private val videoRecorderMock = mockThis<SimulatorVideoRecorder>()

    @Test
    fun videoRecorderDelete() {
        createDeviceForTest()

        whenever(simulatorMock.videoRecorder).thenReturn(videoRecorderMock)

        simulatorsNode.videoRecordingDelete(ref1)

        verify(videoRecorderMock).delete()
    }

    @Test
    fun videoRecorderGet() {
        createDeviceForTest()

        whenever(simulatorMock.videoRecorder).thenReturn(videoRecorderMock)
        val bytes = ByteArray(23)

        whenever(videoRecorderMock.getRecording()).thenReturn(bytes)

        val byteArray = simulatorsNode.videoRecordingGet(ref1)

        assertThat(byteArray, sameInstance(bytes))
    }

    @Test
    fun videoRecorderStart() {
        createDeviceForTest()

        whenever(simulatorMock.videoRecorder).thenReturn(videoRecorderMock)

        simulatorsNode.videoRecordingStart(ref1)

        verify(videoRecorderMock).start()
    }

    @Test
    fun videoRecorderStop() {
        createDeviceForTest()

        whenever(simulatorMock.videoRecorder).thenReturn(videoRecorderMock)

        simulatorsNode.videoRecordingStop(ref1)

        verify(videoRecorderMock).stop()

    }

}
