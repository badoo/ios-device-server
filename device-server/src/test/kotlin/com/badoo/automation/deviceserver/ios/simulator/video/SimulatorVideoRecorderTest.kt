package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceDiagnosticInfo
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SimulatorVideoRecorderTest {
    private val childProcess: ChildProcess = mockThis()
    private val remote = mockThis<IRemote>()
    private val udid = "udid"
    @Suppress("UNUSED_PARAMETER")
    private fun childFactory(
        remoteHost: String, username: String, cmd: List<String>, isInteractiveShell: Boolean, environment: Map<String, String>,
        out_reader: (line: String) -> Unit, err_reader: (line: String) -> Unit
    ): ChildProcess? {
        return childProcess
    }

    private val fbsimctl: FBSimctl = mockThis()
    private val info: FBSimctlDeviceDiagnosticInfo = FBSimctlDeviceDiagnosticInfo("", "", "")

    @Before
    fun setUp() {
        whenever(remote.hostName).thenReturn("hostname")
        whenever(remote.userName).thenReturn("username")
        whenever(remote.fbsimctl).thenReturn(fbsimctl)
        whenever(fbsimctl.diagnose(any())).thenReturn(info)
    }

    @Test
    fun stopFails() {
        val recording = SimulatorVideoRecorder(udid, remote, ::childFactory, Duration.ofMillis(10))
        assertFailsWith<SimulatorVideoRecordingException> { recording.stop() }
    }

    @Test
    fun doubleStartFails() {
        val recording = SimulatorVideoRecorder(udid, remote, ::childFactory, Duration.ofMillis(10))
        recording.start()
        assertFailsWith<SimulatorVideoRecordingException> { recording.start() }
    }

    @Test
    fun stopSuccess() {
        val recording = SimulatorVideoRecorder(udid, remote, ::childFactory, Duration.ofMillis(10))
        recording.start()
        recording.stop()
        assertFalse(recording.isProcessAlive, "Child process should not be alive")
    }
}