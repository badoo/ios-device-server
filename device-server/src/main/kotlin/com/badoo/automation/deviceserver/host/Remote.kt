package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.*
import com.badoo.automation.deviceserver.host.IRemote.Companion.isLocalhost
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlResponseParser
import com.badoo.automation.deviceserver.util.ensure
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.time.Duration

class Remote(
    override val hostName: String,
    override val userName: String,
    override val publicHostName: String,
    private val localExecutor: IShellCommand = ShellCommand(commonEnvironment = mapOf("HOME" to System.getProperty("user.home"))),
    private val remoteExecutor: IShellCommand = getRemoteCommandExecutor(hostName, userName),
    override val fbsimctl: FBSimctl = FBSimctl(remoteExecutor, FBSimctlResponseParser())
) : IRemote {
    companion object {
        const val SSH_AUTH_SOCK = "SSH_AUTH_SOCK"

        fun getRemoteCommandExecutor(hostName: String, userName: String): IShellCommand {
            return if (isLocalhost(hostName, userName)) {
                ShellCommand(commonEnvironment = mapOf("HOME" to System.getProperty("user.home")))
            } else {
                RemoteShellCommand(hostName, userName)
            }
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to hostName
    ))
    private val userAtHost = if (userName.isBlank()) hostName else "$userName@$hostName"

    override fun toString(): String = "<Remote user:$userName node:$hostName>"

    override fun isReachable(): Boolean {
        //FIXME: We need a reliable way to determine if node is available. SSH request might just time-out if node is under heavy load.
        return isReachableBySSH()
    }

    private fun isReachableBySSH(): Boolean {
        return try {
            remoteExecutor.exec(listOf("echo", "1"), returnFailure = true, timeOut = Duration.ofSeconds(15)).isSuccess
        } catch (e: SshConnectionException) {
            false
        }
    }

    override fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult {
        return remoteExecutor.exec(command, env, returnFailure = returnFailure, timeOut = Duration.ofSeconds(timeOutSeconds))
    }

    override fun escape(value: String) = remoteExecutor.escape(value)

    override fun shell(command: String, returnOnFailure: Boolean): CommandResult {
        val cmd = when {
            isLocalhost() -> listOf("bash", "-c", command)
            else -> listOf("bash", "-c", ShellUtils.escape(command)) // workaround for how ssh executor is designed
        }

        return remoteExecutor.exec(cmd, emptyMap(), returnFailure = returnOnFailure)
    }

    //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigating ByteReadChannel
    override fun captureFile(file: File): ByteArray {
        if (isLocalhost()) {
            if (!file.exists()) {
                throw FileNotFoundException("File $file is not found.")
            }
            return file.readBytes()
        }

        val tempFile = File.createTempFile("remoteFile", ".bin")
        try {
            scpFromRemoteHost(file.absolutePath, tempFile.absolutePath, Duration.ofMinutes(2))
            return tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private enum class Signal(val signal: Int) {
        SIGKILL(9),
        SIGTERM(15);

        override fun toString(): String {
            return signal.toString()
        }
    }

    override fun pkill(matchString: String, force: Boolean) {
        val signal = if (force) { Signal.SIGKILL } else { Signal.SIGTERM }
        execIgnoringErrors(listOf("pkill", "-$signal", "-f", matchString))
    }

    override fun isDirectory(path: String): Boolean {
        return remoteExecutor.exec(listOf("test", "-d", path), mapOf(), returnFailure = true).isSuccess
    }

    override fun rsync(from: String, to: String, flags: Set<String>) {
        val cmd = mutableListOf("/usr/bin/rsync")
        val rsyncFlags = mutableSetOf("--archive", "--partial")
        rsyncFlags.addAll(flags)

        cmd.addAll(rsyncFlags)
        cmd.add(from)
        cmd.add("$userAtHost:$to")

        val env = environmentForRsync()

        logger.debug(logMarker, "Executing rsync command: ${cmd.joinToString(" ")}")
        var result = localExecutor.exec(cmd, env)

        if (!result.isSuccess) {
            logger.warn(logMarker, "Executing second time rsync command: ${cmd.joinToString(" ")}")
            result = localExecutor.exec(cmd)
        }

        ensure(result.isSuccess) {
            logger.error(logMarker, "Executing rsync command failed. Result: $result")
            RuntimeException("Remote $cmd failed with $result")
        }
    }

    override fun scpToRemoteHost(from: String, to: String, timeOut: Duration) {
        val result = localExecutor.exec(listOf("/usr/bin/scp", "-r", from, "$userAtHost:$to"), timeOut = timeOut, returnFailure = true)

        ensure(result.isSuccess) {
            val message = "Copying files to remote host failed with ${result.stdErr}"
            logger.error(logMarker, message)
            RuntimeException(message)
        }
    }

    override fun scpFromRemoteHost(from: String, to: String, timeOut: Duration) {
        val result = localExecutor.exec(listOf("/usr/bin/scp", "-r", "$userAtHost:$from", to), timeOut = timeOut, returnFailure = true)

        if (!result.isSuccess) {
            val message = "Copying files from remote host failed with ${result.stdErr}"
            logger.error(logMarker, message)

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

    private fun environmentForRsync(): MutableMap<String, String> {
        val env = mutableMapOf<String, String>()
        val sshAuthSocket = System.getenv(SSH_AUTH_SOCK)

        if (sshAuthSocket != null) {
            env[SSH_AUTH_SOCK] = sshAuthSocket
        }
        return env
    }
}
