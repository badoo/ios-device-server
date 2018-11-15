package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.net.URI
import kotlin.test.assertEquals

class FbsimctlProcTest {
    @Mock private lateinit var remote: IRemote
    private val udid: UDID = "UDID"
    @Mock private lateinit var endpoint: URI
    @Mock private lateinit var childProcess: ChildProcess
    private lateinit var actualCommand: List<String>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(remote.hostName).thenReturn("hostName")
        whenever(remote.userName).thenReturn("userName")
        whenever(endpoint.port).thenReturn(1)
    }

    @Test
    fun startHeadless() {
        val headless = true
        FbsimctlProc(remote, udid, endpoint, headless, this::childFactory).start()
        val expectedCommand = listOf(
            "/usr/local/bin/fbsimctl",
            "UDID",
            "boot",
            "--direct-launch",
            "--",
            "listen",
            "--http",
            "1"
        )

        assertEquals(expectedCommand, actualCommand, "Wrong command")
    }
    @Test
    fun startHeaded() {
        val headless = false
        FbsimctlProc(remote, udid, endpoint, headless, this::childFactory).start()
        val expectedCommand = listOf(
            "/usr/local/bin/fbsimctl",
            "UDID",
            "boot",
            "--",
            "listen",
            "--http",
            "1"
        )

        assertEquals(expectedCommand, actualCommand, "Wrong command")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun childFactory(
        remoteHost: String,
        username: String,
        cmd: List<String>,
        isInteractiveShell: Boolean,
        environment: Map<String, String>,
        out_reader: (line: String) -> Unit,
        err_reader: (line: String) -> Unit
    ): ChildProcess {
        actualCommand = cmd
        return childProcess
    }
}