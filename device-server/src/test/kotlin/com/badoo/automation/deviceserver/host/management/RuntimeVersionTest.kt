package com.badoo.automation.deviceserver.host.management

import org.junit.Assert.*
import org.junit.Test

class RuntimeVersionTest {
    @Test
    fun shouldParseValidRuntime() {
        val rt = RuntimeVersion("iOS 11")

        assertEquals("iOS", rt.name)
        assertEquals(listOf("11"), rt.fragments)
    }

    @Test
    fun shouldParseValidRuntimeWithFragments() {
        val rt = RuntimeVersion("iOS 11.1")

        assertEquals("iOS", rt.name)
        assertEquals(listOf("11", "1"), rt.fragments)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldThrowWhenRuntimeFormatIsInvalid() {
        RuntimeVersion("iOS11")
    }
}
