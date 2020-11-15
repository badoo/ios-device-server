package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.util.CustomHttpClient
import net.logstash.logback.marker.MapEntriesAppendingMarker
import okhttp3.Request
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.lang.RuntimeException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.system.measureNanoTime

class ApplicationBundle(
    val appUrl: URL
) {
    val bundleZip: File by lazy {
        val file = File(appUrl.file)
        File.createTempFile("${file.nameWithoutExtension}.", ".${file.extension}", ApplicationConfiguration().appBundleCachePath)
    }
    private val unzipDirectory by lazy { File(bundleZip.parent, bundleZip.nameWithoutExtension) }
    var appDirectory: File? = null
    private val httpClient = CustomHttpClient.client
        .newBuilder()
        .followRedirects(true)
        .callTimeout(Duration.ofMinutes(10)) // TODO: case when timed out
        .build()

    private var bundleZipSize: Long = -1
    val isDownloaded: Boolean get() = bundleZip.exists() && bundleZipSize > 0 && bundleZipSize == bundleZip.length()

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

    fun downloadApp(logger: Logger, marker: MapEntriesAppendingMarker) {
        try {
            download(appUrl)
        } catch (e: IOException) {
            logger.error(marker, "Failed to download app from url [$appUrl]. Retrying...")
            download(appUrl)
        }
    }

    fun unpack(logger: Logger, marker: MapEntriesAppendingMarker) {
        unzipDirectory.deleteRecursively()

        val nanos = measureNanoTime {
            unzipApp(bundleZip, unzipDirectory)
        }

        logger.debug(marker, "Unzipped app successfully. Took ${TimeUnit.NANOSECONDS.toSeconds(nanos)} seconds")

        val unzipped = unzipDirectory.list()
        if (unzipped.size != 1) {
            throw RuntimeException("Unzipped archive contains too many entries")
        }

        appDirectory = File(unzipDirectory, unzipped.first())
    }

    private fun unzipApp(zipFile: File, unzipDirectory: File) {
        unzipDirectory.mkdirs()

        ZipFile(zipFile.absolutePath).use { zip ->
            zip.entries().asSequence().forEach { zipEntry ->
                val unzippedFile = File(unzipDirectory, zipEntry.name)

                if (zipEntry.isDirectory) {
                    unzippedFile.mkdirs()
                } else {
                    zip.getInputStream(zipEntry).use { input ->
                        unzippedFile.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
        }
    }

    private fun download(url: URL) {
        val request: Request = Request.Builder()
            .get()
            .url(url)
            .build()

        try {
            val httpCall = httpClient.newCall(request)
            val outPath = bundleZip.toPath()

            httpCall.execute().use { response ->
                if (response.code != 200) {
                    throw RuntimeException("Unable to download binary from $url. Response code: ${response.code}. Headers: ${response.headers}. Body: ${response.peekBody(1024).string()}")
                }

                val contentLength = response.headers.get("Content-Length")?.toInt() ?: -1
                response.body!!.byteStream().use { inputStream ->
                    Files.copy(inputStream, outPath, StandardCopyOption.REPLACE_EXISTING)
                }

                val downloadLength = bundleZip.length()
                if (contentLength > 0 && downloadLength != contentLength.toLong()) {
                    throw IOException("Downloaded file size ($downloadLength) different from Content-Length ($contentLength)")
                }

                bundleZipSize = downloadLength
            }
        } catch (e: IOException) {
            bundleZip.delete()
            throw e
        }
    }
}
