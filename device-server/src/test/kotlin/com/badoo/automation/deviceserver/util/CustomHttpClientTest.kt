package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.mockThis
import com.badoo.automation.deviceserver.util.HttpCodes.*
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.whenever
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.URL
import java.net.UnknownHostException

class CustomHttpClientTest {
    @Test fun unknownHost() {
        val httpClient = mockThis<OkHttpClient>()
        whenever(httpClient.newCall(any())).doAnswer { throw UnknownHostException() }
        val client = CustomHttpClient(client = httpClient)
        val result = client.get(URL("http://1922.168.1.6"))
        assertEquals("Wrong code", OriginIsUnreachable.code, result.httpCode)
    }

    @Test fun connectionRefused() {
        val httpClient = mockThis<OkHttpClient>()
        whenever(httpClient.newCall(any())).doAnswer { throw IOException() }
        val client = CustomHttpClient(client = httpClient)
        val result = client.get(URL("http://localhost:1"))
        assertEquals("Wrong code", WebServerIsDown.code, result.httpCode)
    }
}