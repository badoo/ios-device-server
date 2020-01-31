package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.MockitoAnnotations
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.Duration

class RemoteShellCommandTest {
    private lateinit var systemErr: PrintStream
    private lateinit var systemOut: PrintStream
    private lateinit var remoteShell: IShellCommand

    private val remoteHost = "node"
    private val userName = "user"
    private val userAtHost = "user@node"

    @Before
    fun setUp() {
        hideTestOutput() // comment out to debug

        MockitoAnnotations.initMocks(this)
    }

    private fun hideTestOutput() {
        systemErr = System.err
        systemOut = System.out
        val testErr = PrintStream(ByteArrayOutputStream(10_000))
        val testOut = PrintStream(ByteArrayOutputStream(10_000))
        System.setErr(testErr)
        System.setOut(testOut)
    }

    @Test
    fun sshCommandWithEnvironmentVariables() {
        val processBuilder: ProcessBuilder = mockThis()
        val process: Process = mockThis()
        val outputStream: OutputStream = mockThis()
        val stdOutStream: InputStream = mockThis()
        val stdErrStream: InputStream = mockThis()
        whenever(process.inputStream).thenReturn(stdOutStream)
        whenever(process.outputStream).thenReturn(outputStream)
        whenever(process.errorStream).thenReturn(stdErrStream)
        whenever(processBuilder.start()).thenReturn(process)

        remoteShell = RemoteShellCommand(
                remoteHost = remoteHost,
                userName = userName,
                connectionTimeout = 1
                )

        val result = remoteShell.exec(listOf("fbsimctl", "udid='UDID'", "\$PWD"), timeOut = Duration.ofMillis(100), processBuilder = processBuilder)

        val expectedCommand = listOf(
                "/usr/bin/ssh",
                "-o", "ConnectTimeout=1",
                "-o", "PreferredAuthentications=publickey",
                "-q",
                "-t",
                "-t",
                userAtHost,
                "fbsimctl",
                "udid='UDID'",
                "\$PWD"
        )

        assertEquals(expectedCommand, result.cmd)
    }

}
