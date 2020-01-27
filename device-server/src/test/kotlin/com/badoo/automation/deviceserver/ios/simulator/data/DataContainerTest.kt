package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DataContainerTest {

    private val remote: IRemote = mockThis()
    private val fbsimctl: FBSimctl = mockThis()

    private val containerPathStub =
        "/Users/qa/Library/Developer/CoreSimulator/Devices/UDID/data/Containers/Data/Application/A2C79BEC-FD2C-4676-BA9B-B6A62AFE193A"

    @Before
    fun setUp() {
        whenever(remote.fbsimctl).thenReturn(fbsimctl)
    }

    @Test
    fun shouldListFiles() {
        val cmdResult = CommandResult("Caches/\nImage Cache/\nfile.ext\n", "", ByteArray(0), 0)

        whenever(
            remote.execIgnoringErrors(
                Mockito.anyList(),
                Mockito.anyMap(),
                Mockito.anyLong()
            )
        ).thenReturn(cmdResult)

        val container = DataContainer(
            remote = remote,
            basePath = Paths.get(containerPathStub),
            bundleId = "test.bundle"
        )
        val actual = container.listFiles(Paths.get("Library/Caches"))

        val expected = listOf(
            "Caches/",
            "Image Cache/",
            "file.ext"
        )

        assertEquals(expected, actual)
    }

    @Test
    fun shouldReturnEmptyListForEmptyDirectory() {
        val cmdResult = CommandResult("", "", ByteArray(0), 0)

        whenever(
            remote.execIgnoringErrors(
                Mockito.anyList(),
                Mockito.anyMap(),
                Mockito.anyLong()
            )
        ).thenReturn(cmdResult)

        val container = DataContainer(
            remote = remote,
            basePath = Paths.get(containerPathStub),
            bundleId = "test.bundle"
        )
        val actual = container.listFiles(Paths.get("Library/Caches"))

        assertEquals(emptyList(), actual)
    }

    @Test
    fun shouldReadFileAsByteArray() {
        val expected = "123".toByteArray()
        whenever(remote.captureFile(File(containerPathStub, "Library/Caches/file.txt"))).thenReturn(expected)

        val container = DataContainer(
            remote = remote,
            basePath = Paths.get(containerPathStub),
            bundleId = "test.bundle"
        )
        val actual = container.readFile(Paths.get("Library/Caches/file.txt"))

        assertEquals(expected, actual)
    }

    @Test
    fun shouldRejectPathOutsideContainer() {
        val container = DataContainer(
            remote = remote,
            basePath = Paths.get(containerPathStub),
            bundleId = "test.bundle"
        )

        assertFails {
            container.readFile(Paths.get("../file"))
        }
    }
}
