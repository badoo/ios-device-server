package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration

class RemoteShellCommandTest {
    private lateinit var systemErr: PrintStream
    private lateinit var systemOut: PrintStream
    private lateinit var spyProcessBuilder: TestProcessBuilder
    private lateinit var remoteShell: IShellCommand
    @Mock private lateinit var processListener: ShellCommandListener

    private val remoteHost = "node"
    private val userName = "user"
    private val userAtHost = "user@node"

    @Before fun setUp() {
        hideTestOutput() // comment out to debug

        MockitoAnnotations.initMocks(this)
        whenever(processListener.exitCode).thenReturn(0)
        whenever(processListener.stdOut).thenReturn("")
        whenever(processListener.stdErr).thenReturn("")
    }

    private fun hideTestOutput() {
        systemErr = System.err
        systemOut = System.out
        val testErr = PrintStream(ByteArrayOutputStream(10_000))
        val testOut = PrintStream(ByteArrayOutputStream(10_000))
        System.setErr(testErr)
        System.setOut(testOut)
    }

    @Test fun nonInteractiveSshCommand() {
        remoteShell = RemoteShellCommand(
                remoteHost = remoteHost,
                userName = userName,
                builderFactory = ::nuProcessBuilderForTesting,
                connectionTimeout = 1
        )
        remoteShell.exec(listOf("fbsimctl"), timeOut = Duration.ofMillis(100))

        val expectedCommand = listOf(
                "/usr/bin/ssh",
                "-o", "ConnectTimeout=1",
                "-o", "PreferredAuthentications=publickey",
                "-q",
                "-T",
                userAtHost,
            "fbsimctl"
        )

        assertEquals(expectedCommand, spyProcessBuilder.command())
    }

    @Test fun interactiveSshCommand() {
        remoteShell = RemoteShellCommand(
                remoteHost = remoteHost,
                userName = userName,
                builderFactory = ::nuProcessBuilderForTesting,
                isInteractiveShell = true,
                connectionTimeout = 1
                )
        remoteShell.exec(listOf("fbsimctl"), timeOut = Duration.ofMillis(100))

        val expectedCommand = listOf(
                "/usr/bin/ssh",
                "-o", "ConnectTimeout=1",
                "-o", "PreferredAuthentications=publickey",
                "-q",
                "-t", "-t",
                userAtHost,
                "fbsimctl"
        )

        assertEquals(expectedCommand, spyProcessBuilder.command())
    }

    @Test fun sshCommandWithEnvironmentVariables() {
        remoteShell = RemoteShellCommand(
                remoteHost = remoteHost,
                userName = userName,
                builderFactory = ::nuProcessBuilderForTesting,
                connectionTimeout = 1
                )
        remoteShell.exec(listOf("fbsimctl", "udid='UDID'", "\$PWD"), timeOut = Duration.ofMillis(100))

        val expectedCommand = listOf(
                "/usr/bin/ssh",
                "-o", "ConnectTimeout=1",
                "-o", "PreferredAuthentications=publickey",
                "-q",
                "-T",
                userAtHost,
                "fbsimctl",
                "udid='UDID'",
                "\$PWD"
        )

        assertEquals(expectedCommand, spyProcessBuilder.command())
    }

    private class TestProcessBuilder(cmd: List<String>, env: Map<String, String>) : NuProcessBuilder(cmd, env) {
        val mockedProcess: NuProcess = mockThis()
        init {
            whenever(mockedProcess.pid).thenReturn(Int.MAX_VALUE)
        }

        override fun start(): NuProcess {
            return mockedProcess
        }
    }

    private fun nuProcessBuilderForTesting(cmd: List<String>, env: Map<String, String>): NuProcessBuilder {
        spyProcessBuilder = TestProcessBuilder(cmd, env)
        return spyProcessBuilder
    }
}
