package com.badoo.automation.deviceserver.host.management

import org.junit.Assert.*
import org.junit.Test

class XcodeVersionTest {
    @Test
    fun shouldParseXcodeBuildOutput() {
        val out = "Xcode 9.2\nBuild version 9C40b"

        val version = XcodeVersion.fromXcodeBuildOutput(out)

        assertEquals(9, version.major)
        assertEquals(2, version.minor)
    }

    @Test
    fun shouldParseXcodeBuildOutputWithPatchVersion() {
        val out = "Xcode 9.2.1\nBuild version 9C40b"

        val version = XcodeVersion.fromXcodeBuildOutput(out)

        assertEquals(9, version.major)
        assertEquals(2, version.minor)
    }
}
