package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.IRemote.Companion.DEFAULT_PATH
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.pollFor
import com.badoo.automation.deviceserver.util.uriWithPath
import java.io.File
import java.net.URI
import java.net.URL
import java.time.Duration

class AppiumServer(
    private val remote: IRemote,
    private val udid: String,
    private val appiumServerPort: Int,
    private val wdaPort: Int,
    private val childFactory: (
        remoteHost: String,
        username: String,
        cmd: List<String>,
        commandEnvironment: Map<String, String>,
        out_reader: ((line: String) -> Unit)?,
        err_reader: ((line: String) -> Unit)?
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName) {
    private val remoteAppiumTmpDir: File = File(remote.tmpDir, "appium_tmpdir_${udid}")
    private val remoteAppiumServerLog: File = File(remoteAppiumTmpDir, "remote_appium_server_log_${udid}.txt")
    private val appConfig = ApplicationConfiguration()
    private val localAppiumServerLogCopy = File(appConfig.tempFolder, "appium_server_log_${udid}.txt")

    val appiumServerLog
        get(): File {
            return if (remote.isLocalhost()) {
                remoteAppiumServerLog
            } else {
                localAppiumServerLogCopy.delete()
                remote.scpFromRemoteHost(
                    remoteAppiumServerLog.absolutePath,
                    localAppiumServerLogCopy.absolutePath,
                    Duration.ofSeconds(120)
                )
                localAppiumServerLogCopy
            }
        }

    fun deleteAppiumServerLog() {
        remote.shell(": > ${remoteAppiumServerLog.absolutePath}")
        localAppiumServerLogCopy.delete()
    }

    private val statusUrl: URL = uriWithPath(URI("http://${remote.publicHostName}:$appiumServerPort"), "status").toURL()

    override fun checkHealth(): Boolean {
        if (childProcess == null) {
            logger.debug(logMarker, "$this Appium Server has not yet started.")
            return false
        }

        return try {
            logger.trace(logMarker, "Checking health for Appium Server on $udid on url: $statusUrl")
            val result = client.get(statusUrl)
            logger.trace(logMarker, "Appium Server on $udid on url: $statusUrl returned result - ${result.httpCode} , ${result.responseBody}, Success: ${result.isSuccess}")
            return result.isSuccess
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine Appium Server state. Exception: $e")
            false
        }
    }

    override fun start() {
        ensure(childProcess == null) { AppiumServerProcError("Previous Appium Server process $childProcess has not been killed") }
        logger.debug(logMarker, "$this — Starting child process")
        kill() // cleanup old processes in case there are
        deleteRemoteAppiumTmpDir()
        createRemoteAppiumTmpDir()

        val outWriter: ((String) -> Unit)? = null // { message -> logger.info("[Appium Server INFO] $message") }
        val errWriter: (String) -> Unit = { message -> logger.error("[Appium Server ERROR] $message") }

        val path = if (remote.isLocalhost()) {
            "${System.getenv("PATH")}:${DEFAULT_PATH}"
        } else {
            DEFAULT_PATH
        }

        val command = getAppiumServerStartCommand()

        val process = childFactory(
            remote.hostName,
            remote.userName,
            command,
            mapOf("PATH" to path, "TMPDIR" to remote.tmpDir.absolutePath),
            outWriter,
            errWriter
        )

        childProcess = process

        try {
            Thread.sleep(3000) // initial Appium timeout to get process started

            pollFor(
                Duration.ofSeconds(45),
                reasonName = "Waiting for Appium Server to start serving requests",
                retryInterval = Duration.ofSeconds(2),
                logger = logger,
                marker = logMarker
            ) {
                checkHealth()
            }
        } catch (e: Throwable) {
            logger.error(
                logMarker,
                "$this — Appium Server on port: $appiumServerPort failed to start. Detailed log follows:\n${appiumServerLog.readText()}"
            )

            throw e
        }

        logger.debug(logMarker, "$this Appium Server: $childProcess")
    }

    override fun kill() {
        remote.pkill(remoteAppiumTmpDir.absolutePath, false)
        super.kill()
    }

    private fun createRemoteAppiumTmpDir() {
        remote.shell("mkdir -p ${remoteAppiumTmpDir.absolutePath}")
        remote.shell("touch ${remoteAppiumServerLog.absolutePath}")
    }

    private fun deleteRemoteAppiumTmpDir() {
        remote.shell("rm -rf ${remoteAppiumTmpDir.absolutePath}")
    }

    private fun getAppiumServerStartCommand(): List<String> {
        val logLevel: String = if (remote.isLocalhost()) { "debug" } else { "info" }

        val command = listOf(
            "appium",
            "--allow-cors",
            "--port",
            appiumServerPort.toString(),
            "--driver-xcuitest-webdriveragent-port",
            wdaPort.toString(),
            "--log-level",
            logLevel,
            "--log-timestamp",
            "--log-no-colors",
            "--local-timezone",
            "--log",
            remoteAppiumServerLog.absolutePath,
            "--tmp",
            remoteAppiumTmpDir.absolutePath
        )

        return if (remote.isLocalhost()) {
            command
        } else {
            listOf(
                "/bin/bash",
                "-c",
                "'/usr/bin/env PATH=${DEFAULT_PATH} ${command.joinToString(" ")}'" // important to send PATH in order to launch Appium correctly
            )
        }
    }
}
