package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlAppInfoBundle
import com.badoo.automation.deviceserver.mockThis
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FileSystemTest {

    private val udid: UDID = "udid"
    private val remote: IRemote = mockThis()
    private val fbsimctl: FBSimctl = mockThis()

    private val containerPathStub =
        "/Users/qa/Library/Developer/CoreSimulator/Devices/UDID/data/Containers/Data/Application/A2C79BEC-FD2C-4676-BA9B-B6A62AFE193A/"
    private val bundleInfoStub = FBSimctlAppInfo(
        containerPathStub,
        FBSimctlAppInfoBundle(null, "test.bundle", null, null),
        null
    )

    @Before
    fun setUp() {
        whenever(remote.fbsimctl).thenReturn(fbsimctl)
    }

    @Test
    fun shouldCreateDataContainer() {
        whenever(fbsimctl.listApps(udid)).thenReturn(listOf(bundleInfoStub))

        val container = FileSystem(remote, udid).dataContainer("test.bundle")

        assertEquals(Paths.get(containerPathStub), container.basePath)
    }

    @Test
    fun shouldFailOnNonExistingBundleId() {
        whenever(fbsimctl.listApps(udid)).thenReturn(listOf(bundleInfoStub))


        assertFails {
            FileSystem(remote, udid).dataContainer("non-existing.bundle.id")
        }
    }
}
