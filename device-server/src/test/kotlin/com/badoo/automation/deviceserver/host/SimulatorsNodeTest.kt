package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.locks.ReentrantLock

class SimulatorsNodeTest {
    private val iRemote: IRemote = mockThis()
    private val fbSimctl: FBSimctl = mockThis()
    private val locationPermissionsLock: ReentrantLock = mockThis()

    init {
        whenever(iRemote.fbsimctl).thenReturn(fbSimctl)
    }

    private val hostChecker: ISimulatorHostChecker = mockThis()

    private val wdaPath = File("some/file/from/wdaPathProc")

    private val iSimulatorProvider: SimulatorProvider = mockThis()

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

    private val applicationConfiguration: ApplicationConfiguration = mockThis()
    private val simulatorFactory: ISimulatorFactory = mockThis()
    private val publicHostName = "hostname"
    private val simulatorsNode1 = SimulatorsNode(
            iRemote,
            publicHostName,
            hostChecker,
            configuredSimulatorLimit,
            2,
            wdaPath,
            applicationConfiguration,
            iSimulatorProvider,
            portAllocator,
            simulatorFactory,
            locationPermissionsLock
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
            5555,
            setOf(1, 2, 3, 4, 37265),
            DeviceInfo("", "", "", "", ""),
            null,
            ActualCapabilities(true, true, true)
    )
    private val expectedDeviceDTOJson = JsonMapper().toJson(expectedDeviceDTO)

    @Before
    fun setup() {
        whenever(applicationConfiguration.simulatorBackupPath).thenReturn("/node/specific/device/set")
        whenever(iSimulatorProvider.deviceSetPath).thenReturn("/node/specific/device/set")
    }

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
        whenever(iSimulatorProvider.provideSimulator(desiredCapabilities, emptySet())).thenReturn(null)
        simulatorsNode.createDeviceAsync(desiredCapabilities)
    }

    @Test
    fun createDeviceAsyncSucceeds() {
        createDeviceForTest()

        verify(simulatorFactory).newSimulator(
                eq("Udid1-rem-ote-node"),
                eq(iRemote),
                eq(fbsimulatorDevice),
                eq(DeviceAllocatedPorts(1, 2, 3, 4)),
                eq("/node/specific/device/set"),
                eq(File("some/file/from/wdaPathProc")),
                any(),
                eq(false),
                eq(false)
        )
        verify(simulatorMock).prepareAsync()
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
        var fbsimmock = whenever(iSimulatorProvider.provideSimulator(eq(desiredCapabilities), any()))
        simulatorMocks.forEach { pair ->
            fbsimmock = fbsimmock.thenReturn(pair.second)
        }

        var simfac = whenever(simulatorFactory.newSimulator(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        simulatorMocks.forEach { pair ->
            simfac = simfac.thenReturn(pair.first)
        }

        simulatorMocks.forEachIndexed { index, pair ->
            val it = pair.first
            whenever(it.ref).thenReturn("someref$index")
            whenever(it.deviceState).thenReturn(DeviceState.CREATING)
            whenever(it.deviceInfo).thenReturn(DeviceInfo("","","","",""))
            whenever(it.userPorts).thenReturn(DeviceAllocatedPorts(1,2,3, 4))
            whenever(it.fbsimctlEndpoint).thenReturn(URI("http://fbsimctl"))
            whenever(it.wdaEndpoint).thenReturn(URI("http://wda"))
            whenever(it.calabashPort).thenReturn(4444 + index)
            whenever(it.mjpegServerPort).thenReturn(5555 + index)
        }
    }


    @Test
    fun countStartsAtZero() {
        assertThat(simulatorsNode.capacityRemaining(desiredCapabilities), equalTo(1F))
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

        verify(simulatorMock).approveAccess(bundleId, locationPermissionsLock)
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
            5555,
                setOf(1,2,3,4, 37265),
                DeviceInfo("", "", "", "", ""),
                null,
                ActualCapabilities(true, true, true)
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
        assertThat(simulatorsNode.capacityRemaining(desiredCapabilities), equalTo(1F))
        createTwoDevicesForTest()
        assertThat(simulatorsNode.capacityRemaining(desiredCapabilities), equalTo(1F/3))
        assertThat(portAllocator.available(), equalTo(2))

        val actual = simulatorsNode.deleteRelease(ref1, "test")
        assertThat(actual, equalTo(true))
        verify(simulatorMock).release(any())
        assertThat(simulatorsNode.capacityRemaining(desiredCapabilities), equalTo(1F/3*2))
        assertThat(portAllocator.available(), equalTo(6))
    }

    @Test @Ignore
    fun resetAsync() {
        createDeviceForTest()

        simulatorsNode.resetAsync(ref1)
        Thread.sleep(1000)
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
    fun videoRecorderStop() {
        createDeviceForTest()
        Thread.sleep(1000)

        whenever(simulatorMock.videoRecorder).thenReturn(videoRecorderMock)

        simulatorsNode.videoRecordingStop(ref1)

        verify(videoRecorderMock).stop()
    }

    @Test
    fun setEnvironmentVariables() {
        createDeviceForTest()

        simulatorsNode.setEnvironmentVariables(ref1, mapOf())

        verify(simulatorMock).setEnvironmentVariables(mapOf())
    }
}
