package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
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
    private val appiumTmpDirPrefix = "appium_tmpdir_${udid}_"
    private val appiumTmpDir = createAppiumTmpDir()
    private val appiumLog: File = File(appiumTmpDir, "appium_log_${udid}.txt")

    private fun createAppiumTmpDir(): File {
        val tmpPath = remote.shell(
            "/usr/bin/mktemp -d -t $appiumTmpDirPrefix",
            returnOnFailure = false
        ).stdOut.trim()

        return File(tmpPath)
    }

    private val statusUrl: URL = uriWithPath(URI("http://127.0.0.1:$appiumServerPort"), "wd/hub/status").toURL()

    private fun getLogContents(): String {
        return if (remote.isLocalhost()) {
            appiumLog.readText()
        } else {
            val localLogFile = File.createTempFile("appium_log_${udid}.txt", ".txt")
            remote.scpFromRemoteHost(appiumLog.absolutePath, localLogFile.absolutePath, Duration.ofSeconds(60))
            val log = localLogFile.readText()
            localLogFile.delete()
            log
        }
    }

    fun truncateAppiumLog() {
        remote.shell(":> $appiumLog", returnOnFailure = false)
    }

    override fun checkHealth(): Boolean {
        if (childProcess == null) {
            logger.debug(logMarker, "$this Appium Server has not yet started.")
            return false
        }

        return try {
            logger.debug(logMarker, "Checking health for Appium Server on $udid on url: $statusUrl")
            val result = client.get(statusUrl)
            logger.debug(
                logMarker,
                "Appium Server on $udid on url: $statusUrl returned result - ${result.httpCode} , ${result.responseBody}, Success: ${result.isSuccess}"
            )
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
        cleanupLogs()

        val outWriter: (String) -> Unit = { message -> logger.info("[Appium Server INFO] $message") }
        val errWriter: (String) -> Unit = { message -> logger.error("[Appium Server ERROR] $message") }

        val process = childFactory(
            remote.hostName,
            remote.userName,
            getAppiumServerStartCommand(),
            mapOf(
                "PATH" to "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Library/Apple/usr/bin"
            ),
            outWriter,
            errWriter
        )

        childProcess = process

        try {
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
                "$this — Appium Server on port: $appiumServerPort failed to start. Detailed log follows:\n${getLogContents()}"
            )

            throw e
        }

        logger.debug(logMarker, "$this Appium Server: $childProcess")
    }

    override fun kill() {
        remote.pkill(appiumTmpDirPrefix, false)
        super.kill()
    }

    private fun cleanupLogs() {
        remote.shell("rm -rf $appiumTmpDir", false)

        if (!remote.isLocalhost()) {
            appiumTmpDir.deleteRecursively()
        }

        remote.shell("mkdir -p $appiumTmpDir", false)
        truncateAppiumLog()
    }

    private fun getAppiumServerStartCommand(): List<String> {
        return listOf(
            "appium",
            "--port",
            appiumServerPort.toString(),
            "--webdriveragent-port",
            wdaPort.toString(),
            "--base-path",
            APPIUM_BASE_PATH,
            "--log-level",
            "debug",
            "warn:info",
            "--log-timestamp",
            "--log-no-colors",
            "--local-timezone",
            "--log",
            appiumLog.absolutePath,
            "--tmp",
            appiumTmpDir.absolutePath
        )
    }
    companion object {
        const val APPIUM_BASE_PATH = "wd/hub"
    }
}