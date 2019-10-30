package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.ios.proc.LongRunningProcessListener
import com.zaxxer.nuprocess.NuProcessBuilder
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Marker
import java.time.Duration
import java.util.concurrent.TimeUnit

class RemoteShellCommand(
    private val remoteHost: String,
    userName: String,
    builderFactory: (cmd: List<String>, env: Map<String, String>) -> NuProcessBuilder = ::defaultNuProcessBuilder,
    commonEnvironment: Map<String, String> = mapOf(),
    isVerboseMode: Boolean = false,
    connectionTimeout: Int = 10
) : ShellCommand(builderFactory, commonEnvironment) {
    private val userAtHost: String = if (userName.isBlank()) { remoteHost } else { "$userName@$remoteHost" }
    override val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to remoteHost))
    private val sshEnv: Map<String, String>
    private val sshCommandPrefix: List<String>
    init {
        //ssh command prefix
        val sshPrefix = arrayListOf<String>()
        sshPrefix.addAll(listOf(
                SSH_COMMAND,
                "-o", "ConnectTimeout=$connectionTimeout",
                "-o", "PreferredAuthentications=publickey",
                QUIET_MODE
        ))

        sshPrefix.addAll(FORCE_PSEUDO_TERMINAL_ALLOCATION)

        if (isVerboseMode) {
            sshPrefix.add("-vvv")
        }

        sshPrefix.add(userAtHost)
        sshCommandPrefix = ArrayList<String>(sshPrefix)

        //ssh environment
        val env = mutableMapOf<String, String>()
        val sshAuthSocket = System.getenv(SSH_AUTH_SOCK)

        if (sshAuthSocket != null) {
            env[SSH_AUTH_SOCK] = sshAuthSocket
        }

        sshEnv = HashMap(env)
    }

    override fun exec(command: List<String>, environment: Map<String, String>, timeOut: Duration,
                      returnFailure: Boolean, logMarker: Marker?,
                      processListener: IShellCommandListener): CommandResult {
        val cmd = getCommandWithSSHPrefix(command)
        val startTime = System.nanoTime()
        val result = super.exec(cmd, getEnvironmentForSSH(), timeOut, returnFailure, logMarker, processListener)
        val elapsedTime = System.nanoTime() - startTime
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedTime)
        val marker = MapEntriesAppendingMarker(
            mapOf(
                LogMarkers.HOSTNAME to remoteHost,
                LogMarkers.SSH_PROFILING_MS to elapsedMillis
            )
        )
        logger.debug(
            marker, "Execution of SSH command took $elapsedMillis ms. Command: $cmd")

        if (result.exitCode == SSH_ERROR) {
            // FIXME: Check stdout and stderr, if they are empty – ssh timeout, otherwise, it is likely to be command error
            val message = "Probably SSH could not connect to node $remoteHost. Result: $result"
            logger.error(logMarker, message)
            throw SshConnectionException(message)
        }

        return result
    }

    override fun startProcess(command: List<String>, environment: Map<String, String>, logMarker: Marker?,
                              processListener: LongRunningProcessListener) {
        super.startProcess(getCommandWithSSHPrefix(command), getEnvironmentForSSH(), logMarker, processListener)
    }

    override fun escape(value: String): String {
        return ShellUtils.escape(value)
    }

    private fun getEnvironmentForSSH(): HashMap<String, String> {
        val envWithSsh = HashMap<String, String>(sshEnv)
        envWithSsh.putAll(envWithSsh)
        return envWithSsh
    }

    private fun getCommandWithSSHPrefix(command: List<String>): ArrayList<String> {
        val commandWithSshPrefix = ArrayList<String>(sshCommandPrefix)
        commandWithSshPrefix.addAll(command)
        return commandWithSshPrefix
    }

    private companion object {
        const val SSH_COMMAND = "/usr/bin/ssh"
        const val QUIET_MODE = "-q"
        val FORCE_PSEUDO_TERMINAL_ALLOCATION = listOf("-t", "-t")
        const val SSH_AUTH_SOCK = "SSH_AUTH_SOCK"
        const val SSH_ERROR = 255
    }
}
