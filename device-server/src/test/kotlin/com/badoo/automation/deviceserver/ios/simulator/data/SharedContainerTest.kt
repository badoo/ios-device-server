package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doNothing
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito
import java.io.File

@Ignore
class SharedContainerTest {
    private val remote: IRemote = mockThis()

    private val sharedContainerPathStub = File("/Users/qa/Library/Developer/CoreSimulator/Devices/UDID/data/")

    @Test(expected = DataContainerException::class)
    fun shouldRaiseErrorOnDeletingOutsideSharedContainer() {
        val container = SharedContainer(
            remote = remote,
            basePath = sharedContainerPathStub
        )
        container.delete(File("/Users/qa/Library").toPath())
    }

    @Test
    fun shouldDeleteFile() {
        val container = SharedContainer(
            remote = remote,
            basePath = sharedContainerPathStub
        )
        val fakeFailToDelete = File(sharedContainerPathStub.path.plus("/config.plist"))
        whenever(remote.shell(any(), any())).thenReturn(CommandResult("", "", 0, pid = 1))

        container.delete(fakeFailToDelete.toPath())
        Mockito.verify(remote, Mockito.times(1)).shell("rm -rf ${fakeFailToDelete.path}", false)
    }

    @Test(expected = DataContainerException::class)
    fun shouldRaiseErrorOnWritingOutsideSharedContainer() {
        val container = SharedContainer(
            remote = remote,
            basePath = sharedContainerPathStub
        )
        container.writeFile(ByteArray(3), File("/Users/qa/Library").toPath())
    }

    @Test
    fun shouldPushFile() {
        val container = SharedContainer(
            remote = remote,
            basePath = sharedContainerPathStub
        )
        whenever(remote.isLocalhost()).thenReturn(false)
        doNothing().`when`(remote).scpToRemoteHost(any(), any(), any())

        val fakeFailLocation = File(sharedContainerPathStub.path.plus("/config.plist"))

        container.writeFile(ByteArray(3), fakeFailLocation.toPath())
        Mockito.verify(remote, Mockito.times(1)).scpToRemoteHost(any(), any(), any())
    }

    @Test(expected = DataContainerException::class)
    fun shouldRaiseErrorOnReadingOutsideSharedContainer() {
        val container = SharedContainer(
            remote = remote,
            basePath = sharedContainerPathStub
        )
        container.readFile( File("/Users/qa/Library/fake_file.txt").toPath())
    }

    @Test
    fun shouldReadFile() {
        val container = SharedContainer(
            remote = remote,
            basePath = sharedContainerPathStub
        )
        whenever(remote.isLocalhost()).thenReturn(false)
        whenever(remote.captureFile(any())).thenReturn(ByteArray(2))

        val fakeFailLocation = File(sharedContainerPathStub.path.plus("/config.plist"))

        container.readFile(fakeFailLocation.toPath())
        Mockito.verify(remote, Mockito.times(1)).captureFile(any())
    }
}
