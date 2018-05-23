package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.anyType
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Test

import org.junit.Assert.*
import org.junit.Before
import org.mockito.ArgumentMatchers.*

class RemoteTest {
    private val localExecutor: IShellCommand = mockThis()
    private val remoteExecutor: IShellCommand = mockThis()
    private lateinit var remote: Remote

    @Before fun setUp() {
        remote = Remote("host", "user", localExecutor, remoteExecutor)
    }

    @Test
    fun isReachableSshError() {
        whenever(remoteExecutor.exec(anyList(), anyMap(), anyType(), anyBoolean(), anyType(), anyType()))
            .thenThrow(SshConnectionException::class.java)

        assertFalse(remote.isReachable())
    }

    @Test
    fun isReachable() {
        val successfulResult = CommandResult("", "", ByteArray(0), 0)
        whenever(remoteExecutor.exec(anyList(), anyMap(), anyType(), anyBoolean(), anyType(), anyType()))
            .thenReturn(successfulResult)

        assertTrue(remote.isReachable())
    }
}