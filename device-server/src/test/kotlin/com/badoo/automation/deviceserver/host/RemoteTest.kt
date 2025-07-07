package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.anyType
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Test

import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.mockito.ArgumentMatchers.*

@Ignore
class RemoteTest {
    private val commandExecutor: IShellCommand = mockThis()
    private val applicationConfiguration: ApplicationConfiguration = mockThis()
    private lateinit var remote: Remote

    @Before fun setUp() {
        whenever(commandExecutor.exec(anyList(), anyMap(), anyType(), anyBoolean(), anyType(), anyType())).thenReturn(
            CommandResult("", "", 0, pid = 1L)
        )
        remote = Remote(
            hostName = "host",
            publicHostName = "",
            commandExecutor = commandExecutor,
            appConfig = applicationConfiguration
        )
    }

    @Test
    fun isReachableSshError() {
        whenever(commandExecutor.exec(anyList(), anyMap(), anyType(), anyBoolean(), anyType(), anyType()))
            .thenThrow(SshConnectionException::class.java)

        assertFalse(remote.isReachable())
    }

    @Test
    fun isReachable() {
        val successfulResult = CommandResult("", "", 0, pid = 1)
        whenever(commandExecutor.exec(anyList(), anyMap(), anyType(), anyBoolean(), anyType(), anyType()))
            .thenReturn(successfulResult)

        assertTrue(remote.isReachable())
    }
}
