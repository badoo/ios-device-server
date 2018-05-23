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

class FBSimctlTest {
    @Mock private lateinit var executor: IShellCommand
    @Mock private lateinit var parser: IFBSimctlResponseParser

    @Before fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test fun mustTrimLastNewLine() {
        whenever(executor.exec(anyList(), anyMap(), anyType(), anyBoolean(), any<Marker>(), anyType())).thenReturn(CommandResult("/a\n", "", ByteArray(0), 0))
        val fbSimctl = FBSimctl(executor, parser)
        val deviceSets = fbSimctl.listDeviceSets()
        Assert.assertEquals("/a", deviceSets)
    }

    @Test(expected = FBSimctlError::class)
    fun shouldThrowWhenNoDeviceSets() {
        whenever(executor.exec(anyList(), anyMap(), anyType(), anyBoolean(), any(), anyType())).thenReturn(CommandResult("asdfa\n", "", ByteArray(0), 0))
        val fbSimctl = FBSimctl(executor, parser)
        fbSimctl.listDeviceSets()
    }
}