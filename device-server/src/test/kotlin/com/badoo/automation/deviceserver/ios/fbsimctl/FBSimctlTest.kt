package com.badoo.automation.deviceserver.ios.fbsimctl

import com.badoo.automation.deviceserver.anyType
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.slf4j.Marker
import java.io.File

class FBSimctlTest {
    @Mock private lateinit var executor: IShellCommand
    @Mock private lateinit var parser: IFBSimctlResponseParser
    private val fbsimctlResponse = """
        {"event_name":"log","timestamp":1533056871,"level":"info","subject":"Running \/usr\/bin\/xcode-select --print-path with environment {\n    HOME = \"\/Users\/vfrolov\";\n}","event_type":"discrete"}
        {"event_name":"list_device_sets","timestamp":1533056871,"subject":"\/a","event_type":"discrete"}
        """.trimIndent()

    @Before fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test fun mustTrimLastNewLine() {
        whenever(executor.exec(anyList(), anyMap(), anyType(), anyBoolean(), any<Marker>(), anyType())).thenReturn(CommandResult(fbsimctlResponse, "", 0, pid = 1))
        val fbSimctl = FBSimctl(executor, File("/usr/local/bin"), FBSimctlResponseParser())
        val deviceSets = fbSimctl.defaultDeviceSet()
        Assert.assertEquals("/a", deviceSets)
    }

    @Test(expected = FBSimctlError::class)
    fun shouldThrowWhenNoDeviceSets() {
        whenever(executor.exec(anyList(), anyMap(), anyType(), anyBoolean(), any(), anyType())).thenReturn(CommandResult("\n", "", 0, pid = 1))
        whenever(parser.parseDeviceSets(anyString())).thenReturn(emptyList())
        val fbSimctl = FBSimctl(executor, File("/usr/local/bin"), parser)
        fbSimctl.defaultDeviceSet()
    }
}
