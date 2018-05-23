package com.badoo.automation.deviceserver.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.test.assertFailsWith

class SupportKtTest {
    @Test
    fun testUriWithPath() {
        val uri = URI("http://localhost:41798/FA2EC53F-E71A-4BAF-8686-840813C5348F/")
        val newUri = uriWithPath(uri, "/list")
        assertEquals("http://localhost:41798/FA2EC53F-E71A-4BAF-8686-840813C5348F/list", newUri.toString())
    }

    @Test
    fun testExecuteWithTimeoutShouldThrowOnTimeout() {
        assertFailsWith<TimeoutException> {
            executeWithTimeout(Duration.ofMillis(500), "Slow") {
                Thread.sleep(1000)
            }
        }
    }

    @Test
    fun testExecuteWithTimeoutShouldReturnReturnValueOfUnderlyingCallable() {
        val actual = executeWithTimeout(Duration.ofSeconds(1), "Preparing simulator") {
            return@executeWithTimeout 1
        }
        assertEquals(1, actual)
    }

    @Test
    fun testExecuteWithTimeoutShouldRethrowOriginalException() {
        assertFailsWith<RuntimeException> {
            executeWithTimeout(Duration.ofSeconds(1), "Preparing simulator") {
                throw RuntimeException("original error")
            }
        }
    }
}