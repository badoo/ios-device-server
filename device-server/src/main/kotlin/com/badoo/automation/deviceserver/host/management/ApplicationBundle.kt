package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.AppBundleDto
import com.badoo.automation.deviceserver.util.CustomHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Duration

class ApplicationBundle(
    val appUrl: String,
    val dsymUrl: String?, // TODO: maybe null
    val bundleId: String
) {
    lateinit var appFile: File
    lateinit var dsymFile: File

    val isAppDownloaded: Boolean get() = ::appFile.isInitialized && appFile.exists() // FIXME: checksum
    val isDsymDownloaded: Boolean get() = ::dsymFile.isInitialized && dsymFile.exists() // FIXME: checksum

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ApplicationBundle

        if (appUrl != other.appUrl) return false

        return true
    }

    override fun hashCode(): Int {
        return appUrl.hashCode()
    }

    fun downloadApp() {
        appFile = download(appUrl.toUrl())
    }

    private val httpClient = CustomHttpClient.client
        .newBuilder()
        .callTimeout(Duration.ofMinutes(5)) // TODO: case when timed out
        .build()

    private fun download(url: URL): File {
        val request: Request = Request.Builder()
            .get()
            .url(url)
            .build()

        val file = File(url.path)
        val localFile: File = File.createTempFile(file.name, file.extension)

        try {
            val httpCall = httpClient.newCall(request)
            localFile.sink().use { sink ->
                httpCall.execute().use { response ->
                    sink.buffer().writeAll(response.body!!.source())
                }
            }

            return localFile
        } catch (e: IOException) {
            localFile.delete()

            throw e
        }
    }

    companion object {
        fun fromAppBundleDto(dto: AppBundleDto): ApplicationBundle {
            return ApplicationBundle(
                dto.appUrl,
                dto.dsymUrl,
                dto.bundleId
            )
        }
    }
}
private fun String.toUrl(): URL = URL(this)