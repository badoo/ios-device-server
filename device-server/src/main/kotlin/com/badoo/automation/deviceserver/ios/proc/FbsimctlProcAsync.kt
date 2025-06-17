package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecordingException
import com.badoo.automation.deviceserver.util.CustomHttpClient
import com.badoo.automation.deviceserver.util.pollFor
import com.badoo.automation.deviceserver.util.uriWithPath
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.time.Duration

open class FbsimctlProcAsync(
    private val remote: IRemote,
    val udid: String,
    val fbsimctlEndpoint: URI,
    val headless: Boolean,
    val deviceRef: DeviceRef,
    private val config: ApplicationConfiguration = ApplicationConfiguration()
) {
    private val uri: URI = uriWithPath(fbsimctlEndpoint, "list")
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.publicHostName,
            LogMarkers.UDID to udid,
            LogMarkers.DEVICE_REF to deviceRef
        )
    )

    override fun toString(): String = "<$udid at ${remote.hostName}:${fbsimctlEndpoint.port}>"

    private val fbsimctlLogFileName = "fbsimctl_${udid}"
    private val fbsimctlLogFile = File(config.tempFolder, "${fbsimctlLogFileName}.log")

    private val remoteFbsimctlLogPath = File(remote.tmpDir, "${fbsimctlLogFileName}.log").absolutePath
    private val remoteFbsimctlPidPath = File(remote.tmpDir, "${fbsimctlLogFileName}.pid").absolutePath

    fun start() {
        logger.debug(logMarker, "Starting fbsimctl process - log: ${fbsimctlLogFileName}")
        val command = listOf(
            config.remoteFbsimctl.absolutePath,
            udid,
            fbsimctlEndpoint.port.toString(),
            remoteFbsimctlLogPath,
            remoteFbsimctlPidPath
        ).joinToString(" ")

        val result = remote.shell(command)

        if (result.isSuccess) {
            logger.info(logMarker, "Started fbsimctl async ${fbsimctlLogFileName}")
        } else {
            val errorMessage =
                "Failed to start fbsimcl async ${fbsimctlLogFileName}. Exit code: ${result.exitCode} StdOut: ${result.stdOut} StdErr: ${result.stdErr}. Log contents: ${getFbsimctlLog()}"
            logger.error(errorMessage)
            throw VideoRecordingException(errorMessage)
        }
    }

    fun isHealthy(): Boolean {
        return checkHealth()
    }


    fun stop() {
        logger.debug(logMarker, "Stopping remote fbsimctl ${fbsimctlLogFileName}")
        val pidResult = remote.shell("cat ${remoteFbsimctlPidPath}")
        if (pidResult.isSuccess) {
            val pid = pidResult.stdOut.trim()
            logger.debug(logMarker, "Stopping fbsimctl process ${fbsimctlLogFileName}. Got PID $pid")
            val killResult = remote.shell("kill -SIGTERM $pid")
            if (killResult.isSuccess) {
                logger.debug(logMarker, "Stopping fbsimctl ${fbsimctlLogFileName}. Successfully sent SIGTERM to PID $pid")
            } else {
                logger.error(logMarker, "Stopping fbsimctl ${fbsimctlLogFileName}. Failure while sending SIGTERM to PID ${pid}. ${killResult.stdErr}")
            }
        }

        val findResult = remote.shell("pgrep -f \"fbsimctl ${udid} listen\"")

        if (findResult.isSuccess) {
            logger.debug(logMarker, "Found fbsimctl ${fbsimctlLogFileName}. Found processes with pgrep. $findResult")
            findResult.stdOut.lines().filter { it.isNotBlank() }.forEach { line ->
                val pid = line.trim()
                val pidResult = remote.shell("ps -o command -p ${pid}")

                if (pidResult.isSuccess) {
                    pidResult.stdOut.trim().lines().forEach { line ->
                        if (line.contains("fbsimctl") && line.contains(udid) && line.contains("listen")) {
                            logger.debug(logMarker, "Stopping fbsimctl process ${fbsimctlLogFileName}. Got PID $pid")
                            val killResult = remote.shell("kill -SIGTERM $pid")
                            if (killResult.isSuccess) {
                                logger.debug(logMarker, "Stopping fbsimctl ${fbsimctlLogFileName}. Successfully sent SIGINT to PID $pid")
                            } else {
                                logger.error(logMarker, "Stopping fbsimctl ${fbsimctlLogFileName}. Failure while sending SIGINT to PID ${pid}. ${killResult.stdErr}")
                            }

                        }
                    }
                }
            }
        }

        var fbsimctlAsyncExited = false
        val duration = Duration.ofSeconds(10)
        pollFor(
            duration,
            reasonName = "Waiting up to ${duration.seconds} seconds for fbsimctl to stop",
            shouldReturnOnTimeout = true,
            retryInterval = Duration.ofMillis(1000),
            logger = logger,
            marker = logMarker
        ) {
            val processList = remote.shell("ps ax")
            if (processList.isSuccess) {
                fbsimctlAsyncExited = processList.stdOut.trim().lines().none { it.contains("fbsimctl") && it.contains(udid) && it.contains("listen") }
                fbsimctlAsyncExited
            } else {
                false
            }
        }

        if (fbsimctlAsyncExited) {
            logger.info(logMarker, "Stopped fbsimctl ${fbsimctlLogFileName}. Successfully waited for fbsimctl to exit")
        } else {
            logger.info(logMarker, "Failed to stop fbsimctl ${fbsimctlLogFileName}. fbsimctl process is still running after waiting for ${duration.seconds} seconds")
        }
    }


    private fun downloadRemoteFile(remotePath: String, localFile: File) {
        try {
            remote.scpFromRemoteHost(remotePath, localFile.absolutePath, Duration.ofSeconds(60))
        } catch (e: FileNotFoundException) {
            logger.error("Failed to find $remotePath at ${remote.hostName}")
        }
    }

    fun getFbsimctlLog(): String {
        if (!remote.isLocalhost()) {
            downloadRemoteFile(remoteFbsimctlLogPath, fbsimctlLogFile)
        }

        return if (fbsimctlLogFile.exists()) {
            fbsimctlLogFile.readText()
        } else {
            "File $fbsimctlLogFile not found"
        }
    }

    private val client: CustomHttpClient = CustomHttpClient()

    fun checkHealth(): Boolean {
        return try {
            logger.debug(logMarker, "Checking health for ${javaClass.simpleName} on $udid on url: $uri")
            val result = client.get(uri.toURL())
            logger.debug(logMarker, "${javaClass.simpleName} on $udid on url: $uri returned result - ${result.httpCode} , ${result.responseBody}, Success: ${result.isSuccess}")

            return if (result.isSuccess) {
                true
            } else {
                logger.debug(logMarker, "Failed ${javaClass.simpleName} health check fbsimctl with HTTP. Result: $result")
                false
            }
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine ${javaClass.simpleName} device state. Exception: $e")
            false
        }
    }
}