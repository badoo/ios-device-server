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
        .followRedirects(true)
        .connectTimeout(Duration.ofMinutes(1))
        .readTimeout(Duration.ofMinutes(4))
        .writeTimeout(Duration.ofMinutes(1))
        .callTimeout(Duration.ofMinutes(6))
        .build()

    private var bundleZipSize: Long = -1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ApplicationBundle

        return appUrl == other.appUrl
    }

    override fun hashCode(): Int {
        return appUrl.hashCode()
    }

    fun downloadApp(logger: Logger, marker: MapEntriesAppendingMarker) {
        val attempts = 5
        repeat(attempts) { attempt ->
            try {
                logger.info(marker, "Download app from url started (attempt ${attempt + 1}) URL: [$appUrl]")
                performDownload(appUrl, logger, marker)
                logger.info(marker, "Download app from url ended successfully (attempt ${attempt + 1}) URL: [$appUrl]")
                return
            } catch (e: IOException) {
                if (attempt == (attempts - 1)) {
                    logger.error(marker, "Failed to download app from url after $attempts attempts URL: [$appUrl]")
                    throw e
                }
                logger.info(marker, "Failed to download app from url (attempt ${attempt + 1}). Will retry.... URL: [$appUrl]")
            }
            Thread.sleep(2000) // Wait before retrying
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

    private fun performDownload(url: URL, logger: Logger, marker: MapEntriesAppendingMarker) {
        val request: Request = Request.Builder()
            .get()
            .url(url)
            .build()

        try {
            val httpCall = httpClient.newCall(request)
            val outPath = bundleZip.toPath()

            httpCall.execute().use { response ->
                if (response.code != 200) {
                    val message = "Unable to download binary from $url. Response code: ${response.code}. Headers: ${response.headers}. Body: ${response.peekBody(1024).string()}"
                    logger.error(marker, message)
                    throw IOException(message)
                }

                val contentLength = response.headers.get("Content-Length")?.toInt() ?: -1
                response.body!!.byteStream().use { inputStream ->
                    Files.copy(inputStream, outPath, StandardCopyOption.REPLACE_EXISTING)
                }

                val downloadLength = bundleZip.length()
                if (contentLength > 0 && downloadLength != contentLength.toLong()) {
                    val message = "Downloaded file size ($downloadLength) different from Content-Length ($contentLength)"
                    logger.error(marker, message)
                    throw IOException(message)
                }

                bundleZipSize = downloadLength
            }
        } catch (e: IOException) {
            val message = "Failed to download binary from $url. Error: ${e.message}"
            logger.error(marker, message, e)
            Files.deleteIfExists(bundleZip.toPath())
            throw e
        }
    }
}
