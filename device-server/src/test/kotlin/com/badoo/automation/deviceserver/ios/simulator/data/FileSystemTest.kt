package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class FileSystemTest {
    private val udid: UDID = "udid"
    private val remote: IRemote = mockThis()
    private val containerPathStub = File("/Users/qa/Library/Developer/CoreSimulator/Devices/UDID/data/Containers/Data/Application/A2C79BEC-FD2C-4676-BA9B-B6A62AFE193A/")

    @Before
    fun setUp() {
        whenever(remote.publicHostName).thenReturn("asdf")
    }

    @Test
    fun shouldCreateDataContainer() {
        whenever(remote.exec(any(),any(),any(),any())).thenReturn(CommandResult(stdOut = containerPathStub.absolutePath, stdErr = "", exitCode = 0, cmd = listOf(), pid = 1L))

        val container = FileSystem(remote, udid).dataContainer("test.bundle")

        assertEquals(containerPathStub, container.basePath)
    }
}
