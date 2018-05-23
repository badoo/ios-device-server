package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.CoreMatchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class SimulatorProviderTest {
    private val remote: IRemote = mockThis()
    private val headless = true
    private val fbSimctl: FBSimctl = mockThis()

    init {
        whenever(remote.fbsimctl).thenReturn(fbSimctl)
    }

    private val provider = SimulatorProvider(remote)

    private val fbSimctlDevice: FBSimctlDevice = mockThis()

    private val dev1 = FBSimctlDevice("arch", "State", "model", "name", "udid-B", "iOS 11")
    private val dev2 = FBSimctlDevice("arch", "State", "model", "name", "udid-A", "os")

    @Test
    fun findByReturnsDeviceIfFound() {
        whenever(fbSimctl.listDevice("udid")).thenReturn(fbSimctlDevice)

        val actual = provider.findBy("udid")

        assertThat(actual, sameInstance(fbSimctlDevice))
    }

    @Test
    fun findByReturnsNullIfNotFound() {
        whenever(fbSimctl.listDevice("udid")).thenReturn(null)

        val actual = provider.findBy("udid")

        assertThat(actual, nullValue())
    }

    @Test
    fun listCachesResultAndStripsMissingModelOrOs() {
        val expected: List<FBSimctlDevice> = listOf(
                dev1,
                FBSimctlDevice("arch", "State", "",      "name", "udid", "os"),
                FBSimctlDevice("arch", "State", "model", "name", "udid", ""),
                dev2
        )
        whenever(fbSimctl.listSimulators())
                .thenReturn(expected)
                .thenThrow(RuntimeException("Expected first invocation to be cached"))
        val actual = provider.list()
        val actual2 = provider.list()

        assertThat(actual, equalTo(listOf(dev1, dev2)))
        assertThat(actual2, equalTo(listOf(dev1, dev2)))
    }

    @Test
    fun createClearsCache() {
        whenever(fbSimctl.create("model1", "os", false)).thenReturn(dev1)
        whenever(fbSimctl.create("model2", "os", false)).thenReturn(dev2)
        whenever(fbSimctl.listSimulators())
                .thenReturn(listOf(dev1))
                .thenReturn(listOf(dev1, dev2))
                .thenThrow(RuntimeException("Expected first invocation to be cached"))
        val actual1 = provider.create("model1", "os", false)
        provider.list()
        val actual2 = provider.create("model2", "os", false)
        provider.list()
        provider.list()

        assertThat(actual1, sameInstance(dev1))
        assertThat(actual2, sameInstance(dev2))
        verify(fbSimctl, times(2)).listSimulators()
    }

    @Test
    fun matchByUuid() {
        whenever(fbSimctl.listDevice("udid")).thenReturn(dev1)
        val actual = provider.match(DesiredCapabilities("udid", "model", "os", headless), emptySet())
        assertThat(actual, sameInstance(dev1))
    }

    @Test
    fun matchByExistingDesiredCaps() {
        whenever(fbSimctl.listSimulators()).thenReturn(listOf(dev1))
        val actual = provider.match(DesiredCapabilities(null, "model", "iOS 11", true), emptySet())
        assertThat(actual, sameInstance(dev1))
    }

    @Test
    fun matchByCreating() {
        whenever(fbSimctl.create("model", "os", true)).thenReturn(dev2)
        val actual = provider.match(DesiredCapabilities(null, "model", "os", headless, existing = false), emptySet())
        assertThat(actual, sameInstance(dev2))
    }
}