package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.AppBundleDto
import com.badoo.automation.deviceserver.util.CustomHttpClient
import okhttp3.Request
import okhttp3.internal.headersContentLength
import okio.buffer
import okio.sink
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

class ApplicationBundle(
    val appUrl: String,
    val dsymUrl: String?, // TODO: maybe null
    val bundleId: String
) {
    @Volatile
    var appFile: File? = null

    val isAppDownloaded: Boolean get() {
        val file = appFile
        return file != null && file.exists() // FIXME: add checksum
    }

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

    fun downloadApp(): File {
        val downloaded = download(appUrl.toUrl())
        appFile = downloaded
        return downloaded
    }

    private val httpClient = CustomHttpClient.client
        .newBuilder()
        .followRedirects(true)
        .callTimeout(Duration.ofMinutes(10)) // TODO: case when timed out
        .build()

    private fun download(url: URL): File {
        val request: Request = Request.Builder()
            .get()
            .url(url)
            .build()

        val file = File(url.path)
        val localFile: File = File.createTempFile("${file.name}.", ".${file.extension}")

        try {
            val httpCall = httpClient.newCall(request)
            val outPath = localFile.toPath()

            httpCall.execute().use { response ->
                response.body!!.byteStream().use { inputStream ->
                    Files.copy(inputStream, outPath, StandardCopyOption.REPLACE_EXISTING)
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