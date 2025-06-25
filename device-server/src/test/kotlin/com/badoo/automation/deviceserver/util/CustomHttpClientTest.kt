package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.util.HttpCodes.*
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.net.URI

class CustomHttpClientTest {
    @Ignore
    @Test fun unknownHost() {
        val client = CustomHttpClient()
        val result = client.get(URI("http://1922.168.1.6").toURL())
        assertEquals("Wrong code", OriginIsUnreachable.code, result.httpCode)
    }

    @Test fun connectionRefused() {
        val client = CustomHttpClient()
        val result = client.get(URI("http://localhost:1").toURL())
        assertEquals("Wrong code", WebServerIsDown.code, result.httpCode)
    }
}