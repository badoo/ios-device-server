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
import java.time.Duration

class Remote(
    override val hostName: String,
    override val userName: String,
    override val publicHostName: String,
    private val localExecutor: IShellCommand = ShellCommand(),
    private val remoteExecutor: IShellCommand = getRemoteCommandExecutor(hostName, userName),
    override val fbsimctl: FBSimctl = FBSimctl(remoteExecutor, FBSimctlResponseParser())
) : IRemote {
    companion object {
        const val SSH_AUTH_SOCK = "SSH_AUTH_SOCK"
        private const val INITIAL_BUFFER_SIZE = 10 * 1024 * 1024 //FIXME: looks arbitrary. taken as an average of video file sizes

        fun getRemoteCommandExecutor(hostName: String, userName: String, isInteractiveShell: Boolean = false): IShellCommand {
            return if (isLocalhost(hostName, userName)) {
                ShellCommand(commonEnvironment = mapOf("HOME" to System.getProperty("user.home")))
            } else {
                RemoteShellCommand(hostName, userName, isInteractiveShell = isInteractiveShell)
            }
        }
    }

    init {
        if (userName.isBlank() && !isLocalhost()) {
            throw RuntimeException("Config for non-localhost nodes must have non-empty 'user'.")
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
        var attempts = 3

        do {
            attempts--

            if (isReachableBySSH()) {
                return true
            }

        } while (attempts > 0)

        logger.error(logMarker, "Node $hostName is NOT reachable by SSH.")
        return false
    }

    private fun isReachableBySSH(): Boolean {
        try {
            return remoteExecutor.exec(listOf("echo", "1"), returnFailure = true).isSuccess
        } catch (e: SshConnectionException) {
            return false
        }
    }

    override fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult {
        return remoteExecutor.exec(command, env, returnFailure = returnFailure, timeOut = Duration.ofSeconds(timeOutSeconds))
    }

    override fun shell(command: String, returnOnFailure: Boolean): CommandResult {
        val cmd = when {
            isLocalhost() -> listOf("bash", "-c", command)
            else -> listOf("bash", "-c", ShellUtils.escape(command)) // workaround for how ssh executor is designed
        }

        return remoteExecutor.exec(cmd, emptyMap(), returnFailure = returnOnFailure)
    }

    //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigating ByteReadChannel
    override fun captureFile(file: File): CommandResult {
        return remoteExecutor.exec(
            listOf("cat", file.absolutePath),
            returnFailure = true,
            processListener = ShellCommandListener(INITIAL_BUFFER_SIZE) //FIXME: probably better to send just size, not the listener
        )
    }

    override fun pkill(matchString: String) {
        execIgnoringErrors(listOf("pkill", "-9", "-f", matchString))
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
            logger.error(logMarker, "Executing rsync command failed. Result: [$result]")
            RuntimeException("Remote $cmd failed with $result")
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
