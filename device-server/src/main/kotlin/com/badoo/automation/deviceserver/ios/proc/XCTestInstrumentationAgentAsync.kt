package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecordingException
import com.badoo.automation.deviceserver.util.*
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit

class XCTestInstrumentationAgentAsync(
    private val remote: IRemote,
    private val wdaBundles: List<WdaBundle>,
    private val deviceInfo: DeviceInfo,
    private val wdaEndpoint: URI,
    private val mjpegServerPort: Int,
    deviceRef: DeviceRef,
    private val isRealDevice: Boolean,
) {
    private val config: ApplicationConfiguration = ApplicationConfiguration()
    private val udid = deviceInfo.udid
    private val derivedDataDir =
        remote.shell("/usr/bin/mktemp -d -t derivedDataDir_$udid", returnOnFailure = false).stdOut.trim()
    private val xctestrunDir =
        remote.shell("/usr/bin/mktemp -d -t xctestRunDir_$udid", returnOnFailure = false).stdOut.trim()
    private val xctestrunFile = "WebDriverAgent_$udid.xctestrun"
    private val xctestrunPath = File(xctestrunDir, xctestrunFile)

    private val xcrunSimctlLogFileName = "xcrun_simctl_log_${udid}"
    private val localXcrunSimctlLogFile = File(config.tempFolder, "${xcrunSimctlLogFileName}.log")
    private val remoteXcrunSimctlLogPath = File(remote.tmpDir, "${xcrunSimctlLogFileName}.log").absolutePath
    private val remoteXcrunSimctlPidPath = File(remote.tmpDir, "${xcrunSimctlLogFileName}.pid").absolutePath

    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.DEVICE_REF to deviceRef,
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private val instrumentationDaBundle = getWdaBundle("sh.calaba.DeviceAgent")
    private val instrumentationWdaBundle = getWdaBundle("com.facebook.WebDriverAgentRunner")

    private fun getInstrumentationBundle(useAppium: Boolean): WdaBundle {
        return if (useAppium) instrumentationWdaBundle else instrumentationDaBundle
    }

    private fun getWdaBundle(instrumentationBundleId: String): WdaBundle {
        return wdaBundles.find {
            it.bundleId.startsWith(instrumentationBundleId) &&
                    (!isRealDevice || it.provisionedDevices.any { it.equals(udid, ignoreCase = true) })
        }
            ?: throw DeviceNotFoundException("Device with $udid does not have any $instrumentationBundleId bundle that has it's udid provisioned")
    }

    private fun downloadRemoteFile(remotePath: String, localFile: File) {
        try {
            remote.scpFromRemoteHost(remotePath, localFile.absolutePath, Duration.ofSeconds(60))
        } catch (e: FileNotFoundException) {
            logger.error(logMarker, "Failed to find $remotePath at ${remote.hostName}")
        }
    }

    fun getRemoteXcrunSimctlLog(): String {
        return if (localXcrunSimctlLogFile.exists()) {
            localXcrunSimctlLogFile.readText()
        } else {
            "File $localXcrunSimctlLogFile not found"
        }
    }


    private fun prepareXctestrunFile(instrumentationBundle: WdaBundle) {
        val xctestRunnerPath: File = instrumentationBundle.xctestRunnerPath()
        val xctestRunnerRelativePath = File(xctestRunnerPath.parentFile.name, xctestRunnerPath.name).toString()
        val instrumentationPort = if (isRealDevice) instrumentationBundle.deviceInstrumentationPort else wdaEndpoint.port
        val xctestRunContents = xctestRunTemplate
            .replace("__DEVICE_AGENT_PORT__", "$instrumentationPort")
            .replace("__DEVICE_AGENT_MJPEG_PORT__", "$mjpegServerPort")
            .replace("__DEVICE_AGENT_BINARY_PATH__", instrumentationBundle.bundlePath().absolutePath)
            .replace("__DEVICE_AGENT_BUNDLE_ID__", instrumentationBundle.bundleId)
            .replace("__BLUEPRINT_NAME__", instrumentationBundle.bundleName)
            .replace("__PRODUCT_MODULE_NAME__", instrumentationBundle.bundleName)
            .replace("__TEST_IDENTIFIER__", instrumentationBundle.testIdentifier)
            .replace("__TESTBUNDLE_DESTINATION_RELATIVE_PATH__", xctestRunnerRelativePath)

            // real device Xcode 15 and iOS 17
            .replace("__DEVICE_AGENT_FULL_PATH_ON_MAC__", instrumentationBundle.bundlePath().absolutePath)

        xctestrunPath.writeText(xctestRunContents)
    }

    private val xctestRunTemplate: String by lazy {
        if (isRealDevice) {
            if (deviceInfo.osMajorVersion() >= 17) {
                xctestrunRealDeviceTemplateXcode15
            } else {
                xctestrunRealDeviceTemplateXcode13
            }
        } else {
            xctestrunSimulatorTemplate
        }
    }

    private val uri: URI get() {
        val statusPath = if (useWebDriverAgent) "status" else "1.0/status"
        return uriWithPath(wdaEndpoint, statusPath)
    }

    override fun toString(): String = "<$udid at ${remote.hostName}:${wdaEndpoint.port}>"

    private fun installHostApp(instrumentationBundle: WdaBundle) {
        remote.fbsimctl.installApp(udid, instrumentationBundle.bundlePath())
        val timeout = 3000L
        logger.debug(logMarker, "Waiting $timeout ms after install")
        Thread.sleep(timeout)
    }

    val deviceAgentLog: File = File.createTempFile("web_driver_agent_log_", ".txt")

    @Volatile
    private var wdaRunnerStarted = false

    @Volatile
    var useWebDriverAgent: Boolean = true // use WebDriverAgent for Appium or DeviceAgent for Calabash

    fun start(useAppium: Boolean) {
        useWebDriverAgent = useAppium
        val instrumentationBundle = getInstrumentationBundle(useAppium)
        ensure(remote.isDirectory(instrumentationBundle.bundlePath().absolutePath)) { WebDriverAgentError("WebDriverAgent ${instrumentationBundle.bundlePath().absolutePath} does not exist or is not a directory") }
        logger.debug(logMarker, "$this — Starting child process WebDriverAgent on: $wdaEndpoint with bundle id: ${instrumentationBundle.bundleId}")

        cleanupLogs()
        prepareXctestrunFile(instrumentationBundle)

        listOf(instrumentationDaBundle.bundleId, instrumentationWdaBundle.bundleId).forEach {
            remote.fbsimctl.uninstallApp(udid, it, false)
        }

        installHostApp(instrumentationBundle)

        val command = listOf(
            config.remoteXcrunSimctl.absolutePath,
            xctestrunPath.absolutePath,
            udid,
            derivedDataDir,
            remoteXcrunSimctlLogPath,
            remoteXcrunSimctlPidPath
        ).joinToString(" ")
        val result = remote.shell(command)

        if (result.isSuccess) {
            logger.info(logMarker, "Started xctest ${xcrunSimctlLogFileName}")
        } else {
            val errorMessage =
                "Failed to start video xctest ${xcrunSimctlLogFileName}. Exit code: ${result.exitCode} StdOut: ${result.stdOut} StdErr: ${result.stdErr}. Log contents: ${getRemoteXcrunSimctlLog()}"
            logger.error(logMarker, errorMessage)
            throw VideoRecordingException(errorMessage)
        }

        Thread.sleep(10_000)

        getRemoteXcrunSimctlLog().lines().forEach { message ->
            if (!wdaRunnerStarted && (message.contains("ServerURLHere") || message.contains("CalabashXCUITestServer started"))) {
                wdaRunnerStarted = true
                logger.debug(logMarker, "$this — WebDriverAgent has reported that it has Started HTTP server on port: ${wdaEndpoint.port} with bundle id: ${instrumentationBundle.bundleId} . Message: $message")
            }
        }

        try {
            pollFor(
                Duration.ofSeconds(75),
                reasonName = "$this Waiting for WebDriverAgent to start serving requests",
                retryInterval = Duration.ofSeconds(10),
                logger = logger,
                marker = logMarker
            ) {
                getRemoteXcrunSimctlLog().lines().forEach { message ->
                    if (!wdaRunnerStarted && (message.contains("ServerURLHere") || message.contains("CalabashXCUITestServer started"))) {
                        wdaRunnerStarted = true
                        logger.debug(logMarker, "$this — WebDriverAgent has reported that it has Started HTTP server on port: ${wdaEndpoint.port} with bundle id: ${instrumentationBundle.bundleId} . Message: $message")
                    }
                }

                wdaRunnerStarted
            }
        } catch (e: InterruptedException) {
           Thread.currentThread().interrupt()
            wdaRunnerStarted = false
            throw e
        } catch (e: Throwable) {
            wdaRunnerStarted = false
            logger.error(logMarker, "$this — WebDriverAgent on: $wdaEndpoint with bundle id: ${instrumentationBundle.bundleId} failed to start. Detailed log follows:")
            getRemoteXcrunSimctlLog().lines().forEach { logger.error("WDA OUT: $it") }
            throw e
        }

        Thread.sleep(2000) // 2 extra should be ok
        logger.debug(logMarker, "$this WDA: has started")
    }

    private fun truncateAgentLog() {
        Files.write(deviceAgentLog.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun cleanupLogs() {
        remote.shell("rm -rf $derivedDataDir", false)
        remote.shell("mkdir -p $derivedDataDir", true)
        remote.shell("rm -f $xctestrunPath", false)
        remote.shell("rm -f $remoteXcrunSimctlLogPath", false)
        remote.localExecutor.exec(listOf("rm", "-f", localXcrunSimctlLogFile.absolutePath))
        truncateAgentLog()
    }

    private fun terminateHostApp() {
        wdaRunnerStarted = false
        listOf(instrumentationDaBundle.bundleId, instrumentationWdaBundle.bundleId).forEach {
            remote.fbsimctl.terminateApp(udid, bundleId = it, raiseOnError = false)
        }

        Thread.sleep(1000)
        remote.pkill(xctestrunFile, false)
        Thread.sleep(3000)
    }

    fun kill() {
        terminateHostApp()
        stop()
    }

    fun stop() {
        logger.debug(logMarker, "Stopping remote xcrun simctl ${derivedDataDir}")

        val pkillResult = remote.pkill(xctestrunFile, false)
        when (pkillResult.exitCode) {
            0 -> logger.debug(logMarker, "Stop remote xcrun simctl ${xctestrunFile}. Successfully sent SIGTERM to all processes matching $xctestrunFile")
            1 -> logger.warn(logMarker, "Stop remote xcrun simctl ${xctestrunFile}. No processes matching $xctestrunFile found")
            else -> logger.error(logMarker, "Stop remote xcrun simctl ${xctestrunFile}. Failure while sending SIGTERM to processes matching $xctestrunFile. ${pkillResult.stdErr}")
        }

        var remoteXcrunSimctlExited = false
        val duration = Duration.ofSeconds(10)
        val startTime = System.nanoTime()
        pollFor(
            duration,
            reasonName = "Waiting up to ${duration.seconds} seconds for remote xcrun simctl to stop",
            shouldReturnOnTimeout = true,
            retryInterval = Duration.ofMillis(500),
            logger = logger,
            marker = logMarker
        ) {
            val processList = remote.shell("ps ax")
            if (processList.isSuccess) {
                remoteXcrunSimctlExited = processList.stdOut.trim().lines().none { it.contains(xctestrunFile) }
                remoteXcrunSimctlExited
            } else {
                remote.pkill(xctestrunFile, false)
                false
            }
        }
        val elapsedTime = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime)

        if (remoteXcrunSimctlExited) {
            logger.info(logMarker, "Stopped xcrun simctl ${xctestrunFile}. Successfully waited for remote xcrun simctl to exit. Took $elapsedTime seconds")
        } else {
            logger.error(logMarker, "Failed to stop remote xcrun simctl ${xctestrunFile}. Remote xcrun simctl process is still running after waiting for $elapsedTime seconds")
        }
    }

    private val client: CustomHttpClient = CustomHttpClient()

    fun isHealthy(): Boolean {
        return checkHealth()
    }

    fun checkHealth(): Boolean {
        if (!wdaRunnerStarted) {
            logger.debug(logMarker, "$this WebDriverAgent has not yet started.")
            return false
        }

        return try {
            val url = uri.toURL()
            val success = client.get(url).isSuccess
            logger.debug(logMarker, "Checking health for WebDriverAgent on $udid on url: $url - Result: ${if (success) "Success" else "Failure"}")
            return success
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine WDA driver state. Exception: $e")
            false
        }
    }

    companion object {
        val xctestrunSimulatorTemplate: String = XCTestInstrumentationAgentAsync::class.java.classLoader
            .getResource("WebDriverAgent-Simulator.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file WebDriverAgent-Simulator.template.xctestrun from resources")
        val xctestrunRealDeviceTemplateXcode13: String = XCTestInstrumentationAgentAsync::class.java.classLoader
            .getResource("WebDriverAgent-RealDevice-Xcode13.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file WebDriverAgent-RealDevice-Xcode13.template.xctestrun from resources")
        val xctestrunRealDeviceTemplateXcode15: String = XCTestInstrumentationAgentAsync::class.java.classLoader
            .getResource("WebDriverAgent-RealDevice-Xcode15.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file WebDriverAgent-RealDevice-Xcode15.template.xctestrun from resources")
    }
}