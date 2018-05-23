package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.util.HttpCodes.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL

class CustomHttpClientTest {
    @Test fun unknownHost() {
        val client = CustomHttpClient()
        val result = client.get(URL("http://1922.168.1.6"))
        assertEquals("Wrong code", OriginIsUnreachable.code, result.httpCode)
    }

    @Test fun connectionRefused() {
        val client = CustomHttpClient()
        val result = client.get(URL("http://localhost:1"))
        assertEquals("Wrong code", WebServerIsDown.code, result.httpCode)
    }
}