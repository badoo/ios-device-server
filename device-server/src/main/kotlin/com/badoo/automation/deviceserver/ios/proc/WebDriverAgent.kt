package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.uriWithPath
import java.net.URI

open class WebDriverAgent(
    protected val remote: IRemote,
    protected val wdaPath: String,
    protected val hostApp: String,
    protected val udid: UDID,
    private val wdaEndpoint: URI,
    private val childFactory: (
                remoteHost: String,
                userName: String,
                cmd: List<String>,
                isInteractiveShell: Boolean,
                out_reader: (line: String) -> Unit,
                err_reader: (line: String) -> Unit
        ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName) {
    private val launchXctestCommand: List<String> = listOf(
            FBSimctl.FBSIMCTL_BIN,
            FBSimctl.RESPONSE_FORMAT,
            udid,
            "launch_xctest",
            wdaPath,
            hostApp,
            "--port",
            wdaEndpoint.port.toString(),
            "--",
            "listen"
    )
    private val uri: URI = uriWithPath(wdaEndpoint, "wda/healthcheck")

    override fun toString(): String = "<$udid at ${remote.hostName}:${wdaEndpoint.port}>"

    override fun start() {
        ensure(hostApp.isNotBlank()) { WebDriverAgentError("WebDriverAgent host application cannot be empty") }
        ensure(childProcess == null) { WebDriverAgentError("Previous WebDriverAgent childProcess $childProcess has not been killed") }
        ensure(remote.isDirectory(wdaPath)) { WebDriverAgentError("WebDriverAgent $wdaPath does not exist or is not a directory") }
        logger.debug(logMarker, "$this — Starting child process")

        terminateHostApp()

        childProcess = childFactory(
                remote.hostName,
                remote.userName,
                launchXctestCommand,
                false,
                { message -> logger.info(logMarker, "${this@WebDriverAgent}: WDA <o>: ${message.trim()}") },
                { message -> logger.warn(logMarker, "${this@WebDriverAgent}: WDA <e>: ${message.trim()}") }
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