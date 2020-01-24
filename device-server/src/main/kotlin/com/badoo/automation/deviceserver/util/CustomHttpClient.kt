package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.util.HttpCodes.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.*
import java.util.concurrent.TimeUnit

class CustomHttpClient(val client: OkHttpClient = defaultHttpClient) {
    companion object {
        private val defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
    }

    fun get(url: URL): HttpResult {
        val request: Request = Request.Builder()
                .get()
                .url(url)
                .build()
        try {
            val result = client.newCall(request).execute()

            return HttpResult(
                    responseBody = result.body?.string() ?: "",
                    httpCode = result.code
            )
        } catch (e: SocketTimeoutException) {
            return HttpResult(ConnectionTimedOut.code)
        } catch (e: UnknownHostException) {
            return HttpResult(OriginIsUnreachable.code)
        } catch (e: ConnectException) {
            return HttpResult(WebServerIsDown.code)
        } catch (e: java.io.EOFException) {
            return HttpResult(NetworkReadTimeoutError.code)
        } catch (e: java.io.IOException) { // we can get connection reset or stream end from iproxy or socat
            return HttpResult(WebServerIsDown.code)
        } catch (e: RuntimeException) {
            return HttpResult(UnknownError.code)
        }
    }

}