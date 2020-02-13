package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.IllegalStateException
import kotlin.test.assertFailsWith

class SimulatorProcessTest {
    private val udid: UDID = "ADB25768-5C9D-487E-A787-D271934B78B0"
    private val deviceRef: DeviceRef = "asdf"
    private val remote = mockThis<IRemote>()
    private val stdOutWithSimulatorPid = """
        41757 launchd_sim /Users/z/Library/Developer/CoreSimulator/Devices/ADB25768-5C9D-487E-A787-D271934B78B0/data/var/run/launchd_bootstrap.plist
        47009 /usr/bin/ssh -o ConnectTimeout=10 -o PreferredAuthentications=publickey -q -t -t z@localhost /usr/bin/pgrep -fl launchd_sim
    """.trimIndent()

    @Test
    fun testSimulatorProcessFound() {
        val simulatorProcess = SimulatorProcess(remote, udid, deviceRef)
        val simulatorFoundCommandResult = CommandResult(stdOutWithSimulatorPid, "", 0, pid = 1)

        whenever(remote.execIgnoringErrors(any(), any(), any()))
            .thenReturn(simulatorFoundCommandResult)

        assertEquals(41757, simulatorProcess.getSimulatorMainProcessPid())
    }

    @Test
    fun testSimulatorProcessNotFound() {
        val simulatorProcess = SimulatorProcess(remote, udid, deviceRef)
        val noSimulatorFoundCommandResult = CommandResult("", "", 1, pid = 1)

        whenever(remote.execIgnoringErrors(any(), any(), any()))
            .thenReturn(noSimulatorFoundCommandResult)

        assertFailsWith<IllegalStateException> {
            simulatorProcess.getSimulatorMainProcessPid()
        }
    }

    @Test
    fun testKillSimulatorProcess() {
        val simulatorProcess = SimulatorProcess(remote, udid, deviceRef)

        whenever(remote.execIgnoringErrors(any(), any(), any()))
            .thenReturn(CommandResult(stdOutWithSimulatorPid, "", 0, pid = 1))
            .thenReturn(CommandResult("", "", 0, pid = 1))

        simulatorProcess.terminateChildProcess("SafariViewService")

        val argumentCaptor = argumentCaptor<List<String>>()
        verify(remote, times(2)).execIgnoringErrors(argumentCaptor.capture(), any(), any())

        val pkillCommand = listOf("/usr/bin/pkill", "-9", "-P", "41757", "-f", "SafariViewService")
        assertEquals(pkillCommand, argumentCaptor.secondValue)
    }
}
