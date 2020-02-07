package com.badoo.automation.deviceserver.ios

import com.badoo.automation.deviceserver.JsonMapper
import com.badoo.automation.deviceserver.util.CustomHttpClient
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.currentStackTrace

class WdaClient(
    private val commandExecutor: URL,
    private var sessionId: String? = null,
    openTimeout: Duration = Duration.ofSeconds(5),
    readTimeout: Duration = Duration.ofSeconds(5)
) {
    class WdaException(message: String): RuntimeException(message)

    private val client: OkHttpClient = CustomHttpClient().client.newBuilder()
        .connectTimeout(openTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    fun attachToSession() {
        val rv = get("status")
        raiseForWdStatus(rv)

        sessionId = rv["sessionId"].textValue()
    }

    fun dismissAlert(): Boolean {
        raiseIfNoSession()

        val rv = post("session/$sessionId/alert/dismiss", emptyMap())

        return when {
            rv["status"].intValue() == 0 -> true
            rv["status"].intValue() == 27 -> false
            else -> {
                raiseForWdStatus(rv)
                throw WdaException("Unexpected state $rv")
            }
        }
    }

    fun alertText(): String? {
        raiseIfNoSession()

        val rv = get("session/$sessionId/alert/text")

        return when {
            rv["status"].intValue() == 0 -> rv["value"].textValue()
            rv["status"].intValue() == 27 -> null
            else -> {
                raiseForWdStatus(rv)
                throw WdaException("Unexpected state $rv")
            }
        }
    }

    private fun get(path: String): JsonNode {
        val url = URL(commandExecutor, path)
        val request = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(url)
            .build()

        val response = client.newCall(request).execute()

        raiseForHttpStatus(response)

        return JsonMapper().readTree(response.body!!.byteStream())
    }

    private fun post(path: String, params: Map<Any, Any>): JsonNode {
        val url = URL(commandExecutor, path)
        val payload = JsonMapper().toJson(params)

        val request = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(mediaType))
            .url(url)
            .build()

        val response = client.newCall(request).execute()

        raiseForHttpStatus(response)

        return JsonMapper().readTree(response.body!!.byteStream())

    }

    private val mediaType: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

    private fun raiseIfNoSession() {
        if (sessionId == null) {

            throw WdaException("${currentStackTrace()[1].methodName} requires session")
        }
    }

    private fun raiseForHttpStatus(response: Response) {
        if (response.isSuccessful) return

        throw WdaException("WDA error ${response.code}: ${response.body!!.string()}")
    }

    private fun raiseForWdStatus(json: JsonNode) {
        if (json["status"].intValue() != 0) {
            throw WdaException("WebDriver returned non zero status ${json["status"]}: ${json["value"]}")
        }
    }

    fun updateAppiumSettings(settings: Map<Any, Any>): JsonNode {
        return post("/session/$sessionId/appium/settings", settings)
    }
}
