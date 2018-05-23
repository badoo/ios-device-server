package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.host.ISimulatorsNode
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.mockThis
import org.hamcrest.Matchers.*
import org.junit.Assert.assertThat
import org.junit.Test
import java.time.Duration

class SessionTest {
    private var host1: ISimulatorsNode = mockThis()
    private var host2: ISimulatorsNode = mockThis()

    private var sillySeconds:  Long = 42L
    private val session = ActiveDevices(currentTimeSeconds = { sillySeconds++ })

    private val releaseAfterSecs = Duration.ofSeconds(5)

    private val deviceRef1: DeviceRef = "hello-1"
    private val deviceRef2: DeviceRef = "hello-2"

    @Test
    fun deviceRefsAreEmptyInitially() {
        assertThat(session.deviceRefs(), empty())
    }

    @Test
    fun registerDevice() {
        session.registerDevice(deviceRef1, host1, releaseAfterSecs, null)
        assertThat(session.deviceRefs().size, equalTo(1))
    }

    @Test
    fun unregisterNodeDevices() {
        session.registerDevice(deviceRef1, host1, releaseAfterSecs, null)
        session.registerDevice(deviceRef2, host2, releaseAfterSecs, null)

        session.unregisterNodeDevices(host1)

        assertThat(session.deviceRefs(), equalTo(setOf(deviceRef2)))
    }

    @Test
    fun tryGetNodeForReturnsDeviceIfPresent() {
        session.registerDevice(deviceRef1, host1, releaseAfterSecs, null)
        session.registerDevice(deviceRef2, host2, releaseAfterSecs, null)
        assertThat(session.getNodeFor(deviceRef1), equalTo(host1))
        assertThat(session.getNodeFor(deviceRef2), equalTo(host2))
    }

    @Test(expected = DeviceNotFoundException::class)
    fun getNodeForThrowsIfAbsent() {
        session.getNodeFor(deviceRef1)
    }

    @Test
    fun getNodeForReturnsDeviceIfPresent() {
        session.registerDevice(deviceRef1, host1, releaseAfterSecs, null)
        session.registerDevice(deviceRef2, host2, releaseAfterSecs, null)
        assertThat(session.getNodeFor(deviceRef1), equalTo(host1))
        assertThat(session.getNodeFor(deviceRef2), equalTo(host2))
    }

    @Test
    fun refreshDevice() {
        session.registerDevice(deviceRef1, host1, releaseAfterSecs, null)
        val preReleaseSeconds = session.nextReleaseAtSeconds()

        session.getNodeFor(deviceRef1)

        assertThat(session.nextReleaseAtSeconds(), greaterThan(preReleaseSeconds))
    }

    @Test
    fun unregisterDeleteDevice() {
        session.registerDevice(deviceRef1, host1, releaseAfterSecs, null)
        session.registerDevice(deviceRef2, host2, releaseAfterSecs, null)
        session.unregisterDeleteDevice(deviceRef2)
        assertThat(session.deviceRefs().size, equalTo(1))
        assertThat(session.deviceRefs().first(), equalTo(deviceRef1))
    }

    @Test
    fun readyForRelease() {
        session.registerDevice(deviceRef1, host1, Duration.ofSeconds(1), null)
        // Implementation detail: secondsSinceEpoch always increments once per call.
        session.registerDevice(deviceRef2, host2, Duration.ofSeconds(1), null)
        val deviceList = session.readyForRelease()
        assertThat(deviceList.size, equalTo(2))
    }

    @Test
    fun nextReleaseAtSeconds() {
        session.registerDevice(deviceRef1, host1, Duration.ofSeconds(10), null)
        assertThat(session.nextReleaseAtSeconds(), equalTo(10L + 42L))
    }
}