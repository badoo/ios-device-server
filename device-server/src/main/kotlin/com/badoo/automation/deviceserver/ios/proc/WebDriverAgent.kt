package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.uriWithPath
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption

open class WebDriverAgent(
    protected val remote: IRemote,
    protected val wdaRunnerXctest: File,
    protected val hostApp: String,
    protected val udid: UDID,
    private val wdaEndpoint: URI,
    port: Int = wdaEndpoint.port,
    mjpegServerPort: Int,
    private val childFactory: (
                remoteHost: String,
                userName: String,
                cmd: List<String>,
                commandEnvironment: Map<String, String>,
                out_reader: ((line: String) -> Unit)?,
                err_reader: ((line: String) -> Unit)?
        ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName), IWebDriverAgent {
    private val launchXctestCommand: List<String> = listOf(
            remote.fbsimctl.fbsimctlBinary,
            udid,
            "launch_xctest",
            wdaRunnerXctest.absolutePath,
            hostApp,
            "--port",
            port.toString(),
            "--mjpeg-server-port",
            mjpegServerPort.toString(),
            "--",
            "listen"
    )
    private val uri: URI = uriWithPath(wdaEndpoint, "status")
    private val daUri: URI = uriWithPath(wdaEndpoint, "1.0/status")

    override fun toString(): String = "<$udid at ${remote.hostName}:${wdaEndpoint.port}>"

    override fun installHostApp() {
        remote.fbsimctl.installApp(udid, wdaRunnerXctest)
    }

    override val deviceAgentLog: File = File.createTempFile("device_agent_log_", ".txt")

    override fun start() {
        ensure(childProcess == null) { WebDriverAgentError("Previous WebDriverAgent childProcess $childProcess has not been killed") }
        ensure(remote.isDirectory(wdaRunnerXctest.absolutePath)) { WebDriverAgentError("WebDriverAgent ${wdaRunnerXctest.absolutePath} does not exist or is not a directory") }
        logger.debug(logMarker, "$this — Starting child process")

        terminateHostApp()

        cleanupLogs(deviceAgentLog)

        childProcess = childFactory(
                remote.hostName,
                remote.userName,
                launchXctestCommand,
                mapOf(),
                { message -> deviceAgentLog.appendText(message + "\n") },
                { message -> deviceAgentLog.appendText(message + "\n") }
        )

        Thread.sleep(5000) // 5 should be ok
        logger.debug(logMarker, "$this WDA: $childProcess")
    }

    private fun cleanupLogs(logFile: File) {
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        Files.write(logFile.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING);
        logger.debug(logMarker, "$this — Starting to log Device Agent events to file ${logFile.absolutePath}")
    }

    protected open fun terminateHostApp() {
        remote.fbsimctl.terminateApp(udid, bundleId = hostApp, raiseOnError = false)
        Thread.sleep(1000)
    }

    override fun checkHealth(): Boolean {
        return try {
            val url = if (wdaRunnerXctest.name.contains("DeviceAgent")) daUri.toURL() else uri.toURL()
            val result = client.get(url)
            if (!result.isSuccess) {
                logger.warn(logMarker, "WDA returned not success result - ${result.httpCode}")
                return result.isSuccess
            }
            return result.isSuccess
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine WDA driver state. Exception: $e")
            false
        }
    }
}
