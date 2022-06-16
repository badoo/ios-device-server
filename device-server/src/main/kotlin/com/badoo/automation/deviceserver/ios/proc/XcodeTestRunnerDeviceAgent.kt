package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.XcodeVersion
import com.badoo.automation.deviceserver.util.*
import java.io.File
import java.net.URI
import java.time.Duration

class XcodeTestRunnerDeviceAgent(
    private val remote: IRemote,
    private val wdaBundle: WdaBundle,
    private val udid: UDID,
    private val wdaEndpoint: URI,
    private val mjpegServerPort: Int,
    private val deviceRef: DeviceRef,
    private val isRealDevice: Boolean,
    private val port: Int = wdaEndpoint.port,
    private val hostApp: String = wdaBundle.bundlePath(remote.isLocalhost()).absolutePath,
    private val childFactory: (
        remoteHost: String,
        userName: String,
        cmd: List<String>,
        commandEnvironment: Map<String, String>,
        out_reader: ((line: String) -> Unit)?,
        err_reader: ((line: String) -> Unit)?
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName), IWebDriverAgent {
    private val derivedDataDir =
        remote.shell("/usr/bin/mktemp -d -t derivedDataDir_$udid", returnOnFailure = false).stdOut.trim()
    private val xctestrunDir =
        remote.shell("/usr/bin/mktemp -d -t xctestRunDir_$udid", returnOnFailure = false).stdOut.trim()
    val xctestrunSuffix = "DeviceAgent_$udid.xctestrun"
    private val xctestrunFile = File(xctestrunDir, xctestrunSuffix)
    private val commonLogMarkerDetails = mapOf(
        LogMarkers.DEVICE_REF to deviceRef,
        LogMarkers.UDID to udid,
        LogMarkers.HOSTNAME to remote.hostName
    )
    private val testRunnerBundleId = wdaBundle.bundleId

    private val xcodeVersion: XcodeVersion by lazy {
        readXcodeVersion()
    }

    private fun readXcodeVersion(): XcodeVersion {
        val xcodeOutput = remote.execIgnoringErrors(listOf("xcodebuild", "-version"))
        return XcodeVersion.fromXcodeBuildOutput(xcodeOutput.stdOut)
    }

    //xcodebuild test-without-building \
    //  -xctestrun /Users/vfrolov/GitHub/ios-device-server-badoo/device-server/xctestrundir/DARunner_fr0l1.xctestrun \
    //  -destination "id=28E475F2-2887-4C06-B290-CF3AF7364313" \
    //  -derivedDataPath /Users/vfrolov/GitHub/ios-device-server-badoo/device-server/da_runner_derived_data_28E475F2-2887-4C06-B290-CF3AF7364313/derived_data_dir
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

    private fun prepareXctestrunFile() {
        val xctestRunContents = xctestRunTemplate
            .replace("__DEVICE_AGENT_PORT__", "$port")
            .replace("__DEVICE_AGENT_MJPEG_PORT__", "$mjpegServerPort")
            .replace("__DEVICE_AGENT_BINARY_PATH__", "$hostApp")
            .replace("__DEVICE_AGENT_BUNDLE_ID__", testRunnerBundleId)

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
            if (xcodeVersion >= XcodeVersion(13, 0)) xctestrunRealDeviceTemplateXcode13 else xctestrunRealDeviceTemplate
        } else {
            xctestrunSimulatorTemplate
        }
    }

    private val uri: URI = uriWithPath(wdaEndpoint, "status")
    private val daUri: URI = uriWithPath(wdaEndpoint, "1.0/status")

    override fun toString(): String = "<$udid at ${remote.hostName}:${wdaEndpoint.port}>"

    override fun installHostApp() {
        remote.fbsimctl.installApp(udid, File(hostApp))
    }

    override val deviceAgentLog: File = File.createTempFile("device_agent_log_", ".txt")

    @Volatile
    private var wdaRunnerStarted = false

    override fun start() {
        ensure(childProcess == null) { WebDriverAgentError("Previous WebDriverAgent childProcess $childProcess has not been killed") }
        ensure(remote.isDirectory(wdaBundle.bundlePath(remote.isLocalhost()).absolutePath)) { WebDriverAgentError("WebDriverAgent ${wdaBundle.bundlePath(remote.isLocalhost()).absolutePath} does not exist or is not a directory") }
        logger.debug(logMarker, "$this — Starting child process WebDriverAgent on port: $port with bundle id: $testRunnerBundleId")

        cleanupLogs()
        prepareXctestrunFile()

        remote.fbsimctl.uninstallApp(udid, testRunnerBundleId, false)
        installHostApp()

        val process = childFactory(
            remote.hostName,
            remote.userName,
            launchXctestCommand,
            mapOf(),
            { message ->
                deviceAgentLog.appendText(message + "\n")
                if (!wdaRunnerStarted && message.contains("Started HTTP server on port")) {
                    wdaRunnerStarted = true
                    logger.debug(logMarker, "$this — WebDriverAgent has reported that it has Started HTTP server on port: $port with bundle id: $testRunnerBundleId . Message: $message")
                }
            },
            { message -> deviceAgentLog.appendText(message + "\n") }
        )

        childProcess = process

        try {
            pollFor(
                Duration.ofSeconds(45),
                reasonName = "$this Waiting for DeviceAgent to start serving requests",
                retryInterval = Duration.ofSeconds(1),
                logger = logger,
                marker = logMarker
            ) {
                wdaRunnerStarted
            }
        } catch (e: Throwable) {
            logger.error(logMarker, "$this — WebDriverAgent on port: $port with bundle id: $testRunnerBundleId failed to start. Detailed log follows:")
            deviceAgentLog.readLines().forEach { logger.error("WDA OUT: $it") }
            throw e
        }

        Thread.sleep(2000) // 2 extra should be ok
        logger.debug(logMarker, "$this WDA: $childProcess")
    }

    private fun cleanupLogs() {
        remote.shell("rm -rf $derivedDataDir", false)
        remote.shell("mkdir -p $derivedDataDir", true)
        remote.shell("rm -f $xctestrunFile", false)
    }

    private fun terminateHostApp() {
        remote.fbsimctl.terminateApp(udid, bundleId = testRunnerBundleId, raiseOnError = false)
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
            val url = if (wdaBundle.bundleId.contains("DeviceAgent")) daUri.toURL() else uri.toURL()
            logger.debug(logMarker, "Checking health for WebDriverAgent on $udid on url: $url")
            val result = client.get(url)
            logger.debug(logMarker, "WDA on $udid on url: $url returned result - ${result.httpCode} , ${result.responseBody}, Success: ${result.isSuccess}")
            return result.isSuccess
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine WDA driver state. Exception: $e")
            false
        }
    }

    companion object {
        val xctestrunSimulatorTemplate: String = XcodeTestRunnerDeviceAgent::class.java.classLoader
            .getResource("DeviceAgent-Simulator.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file DeviceAgent-Simulator.template.xctestrun from resources")
        val xctestrunRealDeviceTemplate: String = XcodeTestRunnerDeviceAgent::class.java.classLoader
            .getResource("DeviceAgent-RealDevice.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file DeviceAgent-RealDevice.template.xctestrun from resources")
        val xctestrunRealDeviceTemplateXcode13: String = XcodeTestRunnerDeviceAgent::class.java.classLoader
            .getResource("DeviceAgent-RealDevice-Xcode13.template.xctestrun")?.readText()
            ?: throw RuntimeException("Failed to read file DeviceAgent-RealDevice-Xcode13.template.xctestrun from resources")
    }
}
