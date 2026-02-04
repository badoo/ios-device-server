package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.SubProcess
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.osMajorVersion
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.errors.DeviceNotFoundException
import com.badoo.automation.deviceserver.util.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration

class XCTestInstrumentationAgent(
    private val remote: IRemote,
    private val wdaBundles: List<WdaBundle>,
    private val deviceInfo: DeviceInfo,
    private val wdaEndpoint: URI,
    private val mjpegServerPort: Int,
    deviceRef: DeviceRef,
    private val isRealDevice: Boolean,
    private val childFactory: (
        remoteHost: String,
        cmd: List<String>,
        commandEnvironment: Map<String, String>,
        out_reader: ((line: String) -> Unit)?,
        err_reader: ((line: String) -> Unit)?
    ) -> SubProcess = SubProcess.Companion::fromCommand
) : LongRunningProc(deviceInfo.udid, remote.hostName) {
    private val udid = deviceInfo.udid
    private val derivedDataDir = File(remote.tmpDir, "derivedData_$udid")
    private val xctestrunDir = File(remote.tmpDir, "xctestRunDir_$udid")
    val deviceAgentLog: File = File(remote.tmpDir, "web_driver_agent_log_$udid.txt")

    val xctestrunFileName = "WebDriverAgent_$udid.xctestrun"
    private val xctestrunFile = File(xctestrunDir, xctestrunFileName)

    private val instrumentationDaBundle = getWdaBundle("sh.calaba.DeviceAgent")
    private val instrumentationWdaBundle = getWdaBundle("com.facebook.WebDriverAgentRunner")

    private fun getInstrumentationBundle(): WdaBundle {
        return instrumentationDaBundle
    }

    private fun getWdaBundle(instrumentationBundleId: String): WdaBundle {
        return wdaBundles.find {
            it.bundleId.startsWith(instrumentationBundleId) &&
                    (!isRealDevice || it.provisionedDevices.any { it.equals(udid, ignoreCase = true) })
        }
            ?: throw DeviceNotFoundException("Device with $udid does not have any $instrumentationBundleId bundle that has it's udid provisioned")
    }

    private val launchXctestCommand: List<String> = listOf(
        "/usr/bin/xcodebuild",
        "test-without-building",
        "-xctestrun",
        xctestrunFile.absolutePath,
        "-destination",
        "id=$udid",
        "-derivedDataPath",
        derivedDataDir.absolutePath
    )

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

        xctestrunFile.writeText(xctestRunContents)
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
        val statusPath = "1.0/status"
        return uriWithPath(wdaEndpoint, statusPath)
    }

    override fun toString(): String = "DeviceAgent at $udid at ${remote.hostName}:${wdaEndpoint.port}"

    private fun installHostApp(instrumentationBundle: WdaBundle) {
        remote.fbsimctl.installApp(udid, instrumentationBundle.bundlePath())
        val timeout = 3000L
        logger.debug("Waiting $timeout ms after install")
        Thread.sleep(timeout)
    }

    @Volatile
    private var wdaRunnerStarted = false

    override fun start() {
        val wdaProcess = subProcess
        ensure(wdaProcess == null || !wdaProcess.isAlive()) { WebDriverAgentError("Previous WebDriverAgent childProcess $subProcess has not been killed") }

        val instrumentationBundle = getInstrumentationBundle()

        ensure(instrumentationBundle.bundlePath().isDirectory) {
            WebDriverAgentError("$instrumentationBundle ${instrumentationBundle.bundlePath().absolutePath} does not exist or is not a directory")
        }

        logger.debug(logMarker, "$this — Starting child process $this with bundle id: ${instrumentationBundle.bundleId} : $instrumentationBundle")

        cleanupLogs()
        prepareXctestrunFile(instrumentationBundle)

        val installedApps = remote.fbsimctl.listApps(udid).map { it.bundle.bundle_id }
        val instrumentationBundleIds = listOf(instrumentationDaBundle.bundleId, instrumentationWdaBundle.bundleId)
        installedApps.forEach { installedBundleId ->
            if (instrumentationBundleIds.contains(installedBundleId)) {
                logger.debug(logMarker, "$this — Uninstalling previously installed app with bundle id: $installedBundleId")
                remote.fbsimctl.uninstallApp(udid, installedBundleId, false)
            }
        }

        val process = childFactory(
            remote.hostName,
            launchXctestCommand,
            mapOf(),
            { message ->
                deviceAgentLog.appendText(message + "\n")
                if (!wdaRunnerStarted && (message.contains("ServerURLHere") || message.contains("CalabashXCUITestServer started"))) {
                    wdaRunnerStarted = true
                    logger.debug(logMarker, "$this — $instrumentationBundle has reported that it has Started HTTP server on port: ${wdaEndpoint.port} with bundle id: ${instrumentationBundle.bundleId} . Message: $message")
                }
            },
            { message -> deviceAgentLog.appendText(message + "\n") }
        )

        subProcess = process

        try {
            pollFor(
                Duration.ofSeconds(120),
                reasonName = "$this Waiting for $instrumentationBundle to start serving requests",
                retryInterval = Duration.ofSeconds(1),
                logger = logger,
                marker = logMarker
            ) {
                wdaRunnerStarted
            }
        } catch (e: Exception) {
            wdaRunnerStarted = false
            logger.error(logMarker, "$this — $instrumentationBundle on: $wdaEndpoint with bundle id: ${instrumentationBundle.bundleId} failed to start. Detailed log follows:")
            deviceAgentLog.readLines().forEach { logger.error("WDA OUT: $it") }
            throw e
        }

        logger.debug(logMarker, "$this $instrumentationBundle: $subProcess")
    }

    private fun truncateAgentLog() {
        if (deviceAgentLog.exists()) {
            Files.write(deviceAgentLog.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
        } else {
            deviceAgentLog.createNewFile()
        }
    }

    private fun cleanupLogs() {
        derivedDataDir.deleteRecursivelyIfExist(logger, logMarker)
        derivedDataDir.ensureDirectoryExists(logger, logMarker)
        xctestrunDir.deleteRecursivelyIfExist(logger, logMarker)
        xctestrunDir.ensureDirectoryExists(logger, logMarker)
        truncateAgentLog()
    }

    override fun kill() {
        wdaRunnerStarted = false
        super.kill()
    }

    override fun checkHealth(): Boolean {
        if (!wdaRunnerStarted) {
            logger.debug(logMarker, "$this WebDriverAgent has not yet started.")
            return false
        }

        return try {
            val url = uri.toURL()
            val success = client.get(url).isSuccess
            logger.debug(logMarker, "Checking health for WebDriverAgent on $udid on url: $url - Result: ${if (success) "Success" else "Failure"}")
            return success
        } catch (e: Exception) {
            logger.warn(logMarker, "Failed to determine WDA driver state. Exception: $e")
            false
        }
    }

    companion object {
        val xctestrunSimulatorTemplate: String = XCTestInstrumentationAgent::class.java.classLoader
            .getResource("WebDriverAgent-Simulator.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file WebDriverAgent-Simulator.template.xctestrun from resources")
        val xctestrunRealDeviceTemplateXcode13: String = XCTestInstrumentationAgent::class.java.classLoader
            .getResource("WebDriverAgent-RealDevice-Xcode13.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file WebDriverAgent-RealDevice-Xcode13.template.xctestrun from resources")
        val xctestrunRealDeviceTemplateXcode15: String = XCTestInstrumentationAgent::class.java.classLoader
            .getResource("WebDriverAgent-RealDevice-Xcode15.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file WebDriverAgent-RealDevice-Xcode15.template.xctestrun from resources")
    }
}
