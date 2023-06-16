package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
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
    private val udid: UDID,
    private val wdaEndpoint: URI,
    private val mjpegServerPort: Int,
    private val deviceRef: DeviceRef,
    private val isRealDevice: Boolean,
    private val childFactory: (
        remoteHost: String,
        userName: String,
        cmd: List<String>,
        commandEnvironment: Map<String, String>,
        out_reader: ((line: String) -> Unit)?,
        err_reader: ((line: String) -> Unit)?
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName) {
    private val derivedDataDir =
        remote.shell("/usr/bin/mktemp -d -t derivedDataDir_$udid", returnOnFailure = false).stdOut.trim()
    private val xctestrunDir =
        remote.shell("/usr/bin/mktemp -d -t xctestRunDir_$udid", returnOnFailure = false).stdOut.trim()
    val xctestrunSuffix = "WebDriverAgent_$udid.xctestrun"
    private val xctestrunFile = File(xctestrunDir, xctestrunSuffix)
    private val commonLogMarkerDetails = mapOf(
        LogMarkers.DEVICE_REF to deviceRef,
        LogMarkers.UDID to udid,
        LogMarkers.HOSTNAME to remote.hostName
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

    private val launchXctestCommand: List<String> = listOf(
        "/usr/bin/xcodebuild",
        "test-without-building",
        "-xctestrun",
        xctestrunFile.absolutePath,
        "-destination",
        "id=$udid",
        "-derivedDataPath",
        derivedDataDir
    )

    private fun prepareXctestrunFile(instrumentationBundle: WdaBundle) {
        val xctestRunnerPath: File = instrumentationBundle.xctestRunnerPath(remote.isLocalhost())
        val xctestRunnerRelativePath = File(xctestRunnerPath.parentFile.name, xctestRunnerPath.name).toString()
        val instrumentationPort = if (isRealDevice) instrumentationBundle.deviceInstrumentationPort else wdaEndpoint.port
        val xctestRunContents = xctestRunTemplate
            .replace("__DEVICE_AGENT_PORT__", "$instrumentationPort")
            .replace("__DEVICE_AGENT_MJPEG_PORT__", "$mjpegServerPort")
            .replace("__DEVICE_AGENT_BINARY_PATH__", instrumentationBundle.bundlePath(remote.isLocalhost()).absolutePath)
            .replace("__DEVICE_AGENT_BUNDLE_ID__", instrumentationBundle.bundleId)
            .replace("__BLUEPRINT_NAME__", instrumentationBundle.bundleName)
            .replace("__PRODUCT_MODULE_NAME__", instrumentationBundle.bundleName)
            .replace("__TEST_IDENTIFIER__", instrumentationBundle.testIdentifier)
            .replace("__TESTBUNDLE_DESTINATION_RELATIVE_PATH__", xctestRunnerRelativePath)


        if (remote.isLocalhost()) {
            xctestrunFile.writeText(xctestRunContents)
        } else {
            val tmpFile = File.createTempFile("xctestRunDir_$udid.", ".xctestrun")
            tmpFile.writeText(xctestRunContents)
            remote.scpToRemoteHost(tmpFile.absolutePath, xctestrunFile.absolutePath)
        }
    }

    private val xctestRunTemplate: String by lazy {
        if (isRealDevice) {
            xctestrunRealDeviceTemplateXcode13
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
        remote.fbsimctl.installApp(udid, instrumentationBundle.bundlePath(remote.isLocalhost()))
        val timeout = 3000L
        logger.debug("Waiting $timeout ms after install")
        Thread.sleep(timeout)
    }

    val deviceAgentLog: File = File.createTempFile("web_driver_agent_log_", ".txt")

    @Volatile
    private var wdaRunnerStarted = false

    override fun start() {
        TODO("Not implemented. Use start(useAppium: Boolean)")
    }

    @Volatile
    var useWebDriverAgent: Boolean = true // use WebDriverAgent for Appium or DeviceAgent for Calabash

    fun start(useAppium: Boolean) {
        ensure(childProcess == null) { WebDriverAgentError("Previous WebDriverAgent childProcess $childProcess has not been killed") }

        useWebDriverAgent = useAppium
        val instrumentationBundle = getInstrumentationBundle(useAppium)
        ensure(remote.isDirectory(instrumentationBundle.bundlePath(remote.isLocalhost()).absolutePath)) { WebDriverAgentError("WebDriverAgent ${instrumentationBundle.bundlePath(remote.isLocalhost()).absolutePath} does not exist or is not a directory") }
        logger.debug(logMarker, "$this — Starting child process WebDriverAgent on: $wdaEndpoint with bundle id: ${instrumentationBundle.bundleId}")

        cleanupLogs()
        prepareXctestrunFile(instrumentationBundle)

        listOf(instrumentationDaBundle.bundleId, instrumentationWdaBundle.bundleId).forEach {
            remote.fbsimctl.uninstallApp(udid, it, false)
        }

        installHostApp(instrumentationBundle)

        val process = childFactory(
            remote.hostName,
            remote.userName,
            launchXctestCommand,
            mapOf(),
            { message ->
                deviceAgentLog.appendText(message + "\n")
                if (!wdaRunnerStarted && (message.contains("ServerURLHere") || message.contains("CalabashXCUITestServer started"))) {
                    wdaRunnerStarted = true
                    logger.debug(logMarker, "$this — WebDriverAgent has reported that it has Started HTTP server on port: ${wdaEndpoint.port} with bundle id: ${instrumentationBundle.bundleId} . Message: $message")
                }
            },
            { message -> deviceAgentLog.appendText(message + "\n") }
        )

        childProcess = process

        try {
            pollFor(
                Duration.ofSeconds(45),
                reasonName = "$this Waiting for WebDriverAgent to start serving requests",
                retryInterval = Duration.ofSeconds(1),
                logger = logger,
                marker = logMarker
            ) {
                wdaRunnerStarted
            }
        } catch (e: Throwable) {
            wdaRunnerStarted = false
            logger.error(logMarker, "$this — WebDriverAgent on: $wdaEndpoint with bundle id: ${instrumentationBundle.bundleId} failed to start. Detailed log follows:")
            deviceAgentLog.readLines().forEach { logger.error("WDA OUT: $it") }
            throw e
        }

        Thread.sleep(2000) // 2 extra should be ok
        logger.debug(logMarker, "$this WDA: $childProcess")
    }

    private fun truncateAgentLog() {
        Files.write(deviceAgentLog.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun cleanupLogs() {
        remote.shell("rm -rf $derivedDataDir", false)
        remote.shell("mkdir -p $derivedDataDir", true)
        remote.shell("rm -f $xctestrunFile", false)
        truncateAgentLog()
    }

    private fun terminateHostApp() {
        wdaRunnerStarted = false
        listOf(instrumentationDaBundle.bundleId, instrumentationWdaBundle.bundleId).forEach {
            remote.fbsimctl.terminateApp(udid, bundleId = it, raiseOnError = false)
        }

        Thread.sleep(1000)
        remote.pkill(xctestrunSuffix, false)
        Thread.sleep(3000)
    }

    override fun kill() {
        terminateHostApp()
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
        } catch (e: RuntimeException) {
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
    }
}
