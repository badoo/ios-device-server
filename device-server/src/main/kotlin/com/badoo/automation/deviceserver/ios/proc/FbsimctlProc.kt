package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.uriWithPath
import java.net.URI

open class FbsimctlProc(
    private val remote: IRemote,
    protected val udid: String,
    protected val fbsimctlEndpoint: URI,
    val headless: Boolean,
    private val childFactory: (
        remoteHost: String,
        username: String,
        cmd: List<String>,
        commandEnvironment: Map<String, String>,
        out_reader: ((line: String) -> Unit)?,
        err_reader: ((line: String) -> Unit)?
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName) {
    private val uri: URI = uriWithPath(fbsimctlEndpoint, "list")
    //private val stdOutFile: File = File.createTempFile("", "")
    //private val stdErrFile: File = File.createTempFile("", "")

    override fun toString(): String = "<$udid at ${remote.hostName}:${fbsimctlEndpoint.port}>"

    override fun start() {
        ensure(childProcess == null) { FbsimctlProcError("Previous fbsimctl process $childProcess has not been killed") }
        logger.debug(logMarker, "$this — Starting child process")

        val outWriter: (String) -> Unit = { logger.debug(logMarker, it.trim()) }
        val errWriter: (String) -> Unit = { logger.warn(logMarker, it.trim()) }

        childProcess = childFactory(
                remote.hostName,
                remote.userName,
                getFbsimctlCommand(),
                mapOf(),
                null, // outReader, // TODO: write to file o.txt
                errWriter  // TODO: write to file e.txt
        )

        logger.debug(logMarker, "$this FBSimCtl: $childProcess")
    }

    override fun checkHealth(): Boolean {
        return try {
            logger.debug(logMarker, "Checking health for ${javaClass.simpleName} on $udid on url: $uri")
            val result = client.get(uri.toURL())
            logger.debug(logMarker, "${javaClass.simpleName} on $udid on url: $uri returned result - ${result.httpCode} , ${result.responseBody}, Success: ${result.isSuccess}")

            return if (result.isSuccess) {
                true
            } else {
                logger.debug(logMarker, "Failed ${javaClass.simpleName} health check. Result: $result")
                false
            }
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine ${javaClass.simpleName} device state. Exception: $e")
            false
        }
    }

    protected open fun getFbsimctlCommand(): List<String> {
        val cmd = listOf(
            remote.fbsimctl.fbsimctlBinary,
            udid,
            "listen",
            "--http",
            fbsimctlEndpoint.port.toString()
        )

        return cmd
    }
}
