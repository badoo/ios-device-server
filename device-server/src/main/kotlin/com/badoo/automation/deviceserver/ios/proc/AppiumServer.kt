package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.IRemote.Companion.DEFAULT_PATH
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.pollFor
import com.badoo.automation.deviceserver.util.uriWithPath
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
    private val remoteAppiumTmpDirPrefix = "appium_tmpdir_${udid}_"
    private val remoteAppiumTmpDir = createRemoteAppiumTmpDir()
    private val remoteAppiumServerLog: File = File(remoteAppiumTmpDir, "remote_appium_server_log_${udid}.txt")
    private val localAppiumServerLog: File = File.createTempFile("appium_server_log_${udid}", ".txt")

    val appiumServerLog get(): File {
        Files.write(localAppiumServerLog.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)

        if (remote.isLocalhost()) {
            localAppiumServerLog.writeBytes(remoteAppiumServerLog.readBytes())
        } else {
            remote.scpFromRemoteHost(remoteAppiumServerLog.absolutePath, localAppiumServerLog.absolutePath, Duration.ofSeconds(60))
        }

        return localAppiumServerLog
    }

    fun appiumServerLogDelete() {
        Files.write(localAppiumServerLog.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)

        if (remote.isLocalhost()) {
            Files.write(remoteAppiumServerLog.toPath(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
        } else {
            remote.shell("cat /dev/null > ${remoteAppiumServerLog.absolutePath}")
        }
    }

    private fun createRemoteAppiumTmpDir(): File {
        return if (remote.isLocalhost()) {
            createTempDir(remoteAppiumTmpDirPrefix)
        } else {
            val tmpPath = remote.shell(
                "/usr/bin/mktemp -d -t $remoteAppiumTmpDirPrefix",
                returnOnFailure = false
            ).stdOut.trim()

            File(tmpPath)
        }
    }

    private val statusUrl: URL = uriWithPath(URI("http://${remote.publicHostName}:$appiumServerPort"), "wd/hub/status").toURL()

    private fun getLogContents(): String {
        return if (remote.isLocalhost()) {
            remoteAppiumServerLog.readText()
        } else {
            val localLogFile = File.createTempFile("tmp_appium_log_${udid}.txt", ".txt")
            remote.scpFromRemoteHost(remoteAppiumServerLog.absolutePath, localLogFile.absolutePath, Duration.ofSeconds(60))
            val log = localLogFile.readText()
            localLogFile.delete()
            log
        }
    }

    fun truncateAppiumLog() {
        remote.shell(":> $remoteAppiumServerLog", returnOnFailure = false)
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

        val path = if (remote.isLocalhost()) {
            "${System.getenv("PATH")}:${DEFAULT_PATH}"
        } else {
            DEFAULT_PATH
        }

        val process = childFactory(
            remote.hostName,
            remote.userName,
            getAppiumServerStartCommand(),
            mapOf("PATH" to path),
            outWriter,
            errWriter
        )

        childProcess = process

        try {
            Thread.sleep(1000) // initial Appium timeout to get process started

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
        remote.pkill(remoteAppiumTmpDirPrefix, false)
        super.kill()
    }

    private fun cleanupLogs() {
        remote.shell("rm -rf $remoteAppiumTmpDir", false)

        if (!remote.isLocalhost()) {
            remoteAppiumTmpDir.deleteRecursively()
        }

        remote.shell("mkdir -p $remoteAppiumTmpDir", false)
        truncateAppiumLog()
    }

    private fun getAppiumServerStartCommand(): List<String> {
        val command = listOf(
            "appium",
            "--allow-cors",
            "--port",
            appiumServerPort.toString(),
            "--webdriveragent-port",
            wdaPort.toString(),
            "--base-path",
            APPIUM_BASE_PATH,
            "--log-level",
            "info",
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
    companion object {
        const val APPIUM_BASE_PATH = "wd/hub"
    }
}