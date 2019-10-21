package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.uriWithPath
import java.io.File
import java.net.URI

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
                isInteractiveShell: Boolean,
                out_reader: ((line: String) -> Unit)?,
                err_reader: ((line: String) -> Unit)?
        ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName), IWebDriverAgent {
    private val launchXctestCommand: List<String> = listOf(
            FBSimctl.FBSIMCTL_BIN,
            udid,
            "launch_xctest",
            wdaRunnerXctest.absolutePath,
            hostApp,
            "--port",
            port.toString(),
            "--mjpeg-server-port",
            mjpegServerPort.toString(),
            "--mjpeg-server-frame-rate",
            "4",
            "--",
            "listen"
    )
    private val uri: URI = uriWithPath(wdaEndpoint, "status")

    override fun toString(): String = "<$udid at ${remote.hostName}:${wdaEndpoint.port}>"

    override fun installHostApp() {
        remote.fbsimctl.installApp(udid, wdaRunnerXctest)
    }

    override fun start() {
        ensure(childProcess == null) { WebDriverAgentError("Previous WebDriverAgent childProcess $childProcess has not been killed") }
        ensure(remote.isDirectory(wdaRunnerXctest.absolutePath)) { WebDriverAgentError("WebDriverAgent ${wdaRunnerXctest.absolutePath} does not exist or is not a directory") }
        logger.debug(logMarker, "$this — Starting child process")

        terminateHostApp()

        childProcess = childFactory(
                remote.hostName,
                remote.userName,
                launchXctestCommand,
                mapOf("MJPEG_SERVER_FRAMERATE" to "4"),
                false,
                null,
                { message -> logger.debug(logMarker, "${this@WebDriverAgent}: WDA <e>: ${message.trim()}") }
        )

        Thread.sleep(5000) // 5 should be ok
        logger.debug(logMarker, "$this WDA: $childProcess")
    }

    protected open fun terminateHostApp() {
        remote.fbsimctl.terminateApp(udid, bundleId = hostApp, raiseOnError = false)
        Thread.sleep(1000)
    }

    override fun checkHealth(): Boolean {
        return try {
            val result = client.get(uri.toURL())
            return result.isSuccess
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine WDA driver state. Exception: $e")
            false
        }
    }
}