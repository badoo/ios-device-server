package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.Xcode
import com.badoo.automation.deviceserver.host.management.XcodeVersion
import com.nhaarman.mockito_kotlin.whenever
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

internal class XcodeFinderTest {
    @Before
    fun setUp() {

    }

    @Test
    fun testFindTwoXcodeApps() {
        val mdfindResult = """
            /Applications/Xcode-10.0.app
            /Applications/Xcode-9.4.1.app
        """.trimIndent()
        val remote: IRemote = mockThis()

        val mdfindCommandResult = CommandResult(stdOut = mdfindResult, stdErr = "", stdOutBytes = mdfindResult.toByteArray(), exitCode = 0)
        whenever(remote.exec(listOf("mdfind", "kMDItemCFBundleIdentifier == 'com.apple.dt.Xcode'"), mapOf(), false, 30)).thenReturn(mdfindCommandResult)

        val env9 = mapOf(Xcode.DEVELOPER_DIR to "/Applications/Xcode-9.4.1.app/Contents/Developer")
        whenever(remote.exec(listOf("xcodebuild", "-version"), env9, false, 30))
            .thenReturn(CommandResult(stdOut = "Xcode 9.4.1\nBuild version 9F2000\n", stdErr = "", stdOutBytes = mdfindResult.toByteArray(), exitCode = 0))

        val env10 = mapOf(Xcode.DEVELOPER_DIR to "/Applications/Xcode-10.0.app/Contents/Developer")
        whenever(remote.exec(listOf("xcodebuild", "-version"), env10, false, 30))
            .thenReturn(CommandResult(stdOut = "Xcode 10.0\nBuild version 10A255\n", stdErr = "", stdOutBytes = mdfindResult.toByteArray(), exitCode = 0))

        val actualXcodeApps = XcodeFinder(remote).findInstalledXcodeApplications()

        val expected = listOf(
            Xcode(XcodeVersion(10, 0), File("/Applications/Xcode-10.0.app/Contents/Developer")),
            Xcode(XcodeVersion(9, 4), File("/Applications/Xcode-9.4.1.app/Contents/Developer"))
        )

        assertEquals(expected, actualXcodeApps)
    }
}