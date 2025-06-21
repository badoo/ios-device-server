package com.badoo.automation.deviceserver.host

import XCRunSimctl
import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.*
import com.badoo.automation.deviceserver.command.SshConnectionException
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlResponseParser
import com.badoo.automation.deviceserver.util.ensure
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.time.Duration
import java.time.Duration.ofSeconds

class Remote(
    override val hostName: String,
    override val userName: String,
    override val publicHostName: String,
    override val localExecutor: IShellCommand = ShellCommand(),
    override val remoteExecutor: IShellCommand = ShellCommand(),
    override val fbsimctl: FBSimctl = FBSimctl(remoteExecutor, getHomeBrewPath(remoteExecutor), FBSimctlResponseParser()),
    override val xcrunSimctl: XCRunSimctl = XCRunSimctl(remoteExecutor),
    appConfig: ApplicationConfiguration = ApplicationConfiguration()
) : IRemote {
    companion object {
        fun getLocalCommandExecutor(): IShellCommand {
            return ShellCommand()
        }

        fun getHomeBrewPath(executor: IShellCommand): File {
            return when {
                executor.exec(listOf("test", "-d", "/opt/homebrew/Cellar")).isSuccess -> File("/opt/homebrew/bin")
                executor.exec(listOf("test", "-d", "/usr/local/Cellar")).isSuccess -> File("/usr/local/bin")
                else -> throw RuntimeException("Failed to find Homebrew directory")
            }
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to hostName
    ))
    private val userAtHost = if (userName.isBlank()) hostName else "$userName@$hostName"

    override fun toString(): String = "<Remote user:$userName node:$hostName>"

    override val homeBrewPath: File by lazy {
        getHomeBrewPath(remoteExecutor)
    }

    override val tmpDir: File = appConfig.tempFolder

    override fun isReachable(): Boolean {
        return try {
            remoteExecutor.exec(listOf("echo", "1"), returnFailure = true, timeOut = ofSeconds(20)).isSuccess
        } catch (e: SshConnectionException) {
            false
        }
    }

    override fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult {
        return remoteExecutor.exec(command, env, returnFailure = returnFailure, timeOut = Duration.ofSeconds(timeOutSeconds))
    }

    override fun escape(value: String) = remoteExecutor.escape(value)

    override fun shell(command: String, returnOnFailure: Boolean, environment: Map<String, String>): CommandResult {
        val cmd = listOf("bash", "-c", command)

        return try {
            remoteExecutor.exec(cmd, environment, returnFailure = returnOnFailure)
        } catch (e: SshConnectionException) {
            logger.error("Remote retrying shell command on SSH error. Command: $cmd")
            remoteExecutor.exec(cmd, environment, returnFailure = returnOnFailure)
        }
    }

    //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigating ByteReadChannel
    override fun captureFile(file: File): ByteArray {
        if (!file.exists()) {
            throw FileNotFoundException("File $file is not found.")
        }
        return file.readBytes()
    }

    private enum class Signal(val signal: Int) {
        SIGKILL(9),
        SIGTERM(15);

        override fun toString(): String {
            return signal.toString()
        }
    }

    override fun pkill(matchString: String, force: Boolean): CommandResult {
        val signal = if (force) { Signal.SIGKILL } else { Signal.SIGTERM }
        return execIgnoringErrors(listOf("pkill", "-$signal", "-f", matchString))
    }

    override fun isDirectory(path: String): Boolean {
        return remoteExecutor.exec(listOf("test", "-d", path), mapOf(), returnFailure = true).isSuccess
    }

    override fun scpToRemoteHost(from: String, to: String, timeOut: Duration) {
        val result = localExecutor.exec(listOf("/usr/bin/scp", "-P", "2222", "-r", from, "$userAtHost:$to"), timeOut = timeOut, returnFailure = true)

        ensure(result.isSuccess) {
            val message = "Copying files to remote host failed with ${result.stdErr}"
            val stackTrace = Thread.currentThread().stackTrace.joinToString("\n")
            val marker = MapEntriesAppendingMarker(mapOf("stack_trace" to stackTrace))
            marker.add(logMarker)
            logger.error(marker, message, stackTrace)
            RuntimeException(message)
        }
    }

    override fun scpFromRemoteHost(from: String, to: String, timeOut: Duration) {
        val result = localExecutor.exec(listOf("/usr/bin/scp", "-P", "2222", "-r", "$userAtHost:$from", to), timeOut = timeOut, returnFailure = true)

        if (!result.isSuccess) {
            val message = "Copying files from remote host failed with ${result.stdErr}"
            val stackTrace = Thread.currentThread().stackTrace.joinToString("\n")
            val marker = MapEntriesAppendingMarker(mapOf("stack_trace" to stackTrace))
            marker.add(logMarker)
            logger.error(logMarker, message, stackTrace)

            throw if (result.stdErr.contains("No such file or directory")) {
                FileNotFoundException(message)
            } else {
                RuntimeException(message)
            }
        }
    }

    override fun rm(path: String, timeOut: Duration) {
        val result = remoteExecutor.exec(listOf("/bin/rm", "-rf", path), timeOut = timeOut, returnFailure = true)

        ensure(result.isSuccess) {
            val message = "Failed to delete remote files. Stderr: ${result.stdErr}"
            logger.error(logMarker, message)
            RuntimeException(message)
        }
    }
}
