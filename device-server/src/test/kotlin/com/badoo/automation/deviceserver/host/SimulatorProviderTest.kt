package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Before
import org.junit.Test

class SimulatorProviderTest {
    private val remote: IRemote = mockThis()
    private val headless = true
    private val fbSimctl: FBSimctl = mockThis()
    private val provider = SimulatorProvider(remote, "/Users/qa/asdf")
    private val dev1 = FBSimctlDevice("arch", "State", "model", "name", "udid-B", "iOS 11")
    private val dev2 = FBSimctlDevice("arch", "State", "model", "name", "udid-A", "os")
    val anyListType = listOf<String>("", "")::class
    val anyMapType = mapOf<String, String>()::class
    val anyBooleanType = false::class
    val anyLongType = 1L::class

    @Before
    fun setup() {
        whenever(remote.fbsimctl).thenReturn(fbSimctl)
        println(anyListType)
        println(anyMapType)
        println(anyBooleanType)
    }

    @Test
    fun matchByUuid() {
        val result = CommandResult("udid-A_BACKUP\nudid-B_BACKUP\nudid-C_BACKUP\n", "", 0, true, listOf("/bin/ls"), 1)
        whenever(remote.exec(command = listOf("/bin/ls", "-1", "/Users/qa/asdf"), env = mapOf<String, String>(), returnFailure = false, timeOutSeconds = 60L)).thenReturn(result)
        whenever(fbSimctl.listSimulators()).thenReturn(listOf(dev1))
        whenever(remote.fbsimctl.defaultDeviceSet()).thenReturn("/Users/qa/CoreSimulator")
        val actual = provider.provideSimulator(DesiredCapabilities("udid-B", "model", "os", headless), emptySet())
        assertThat(actual, sameInstance(dev1))
    }

    @Test
    fun matchByExistingDesiredCaps() {
        whenever(fbSimctl.listSimulators()).thenReturn(listOf(dev1))
        val result = CommandResult("udid-A_BACKUP\nudid-B_BACKUP\nudid-C_BACKUP\n", "", 0, true, listOf("/bin/ls"), 1)
        whenever(remote.exec(command = listOf("/bin/ls", "-1", "/Users/qa/asdf"), env = mapOf<String, String>(), returnFailure = false, timeOutSeconds = 60L)).thenReturn(result)
        val actual = provider.provideSimulator(DesiredCapabilities(null, "model", "iOS 11", true), emptySet())
        assertThat(actual, sameInstance(dev1))
    }

    @Test
    fun matchByCreating() {
        whenever(fbSimctl.create("model", "os")).thenReturn(dev2)
        val actual = provider.provideSimulator(DesiredCapabilities(null, "model", "os", headless, existing = false), emptySet())
        assertThat(actual, sameInstance(dev2))
    }
}