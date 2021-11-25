package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Marker
import java.time.Duration
import java.util.concurrent.TimeUnit

class RemoteShellCommand(
    private val remoteHost: String,
    userName: String,
    commonEnvironment: Map<String, String> = mapOf(),
    private val isVerboseMode: Boolean = false,
    private val connectionTimeout: Int = 15
) : ShellCommand(commonEnvironment) {
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
                      returnFailure: Boolean, logMarker: Marker?, processBuilder: ProcessBuilder): CommandResult {
        val cmd = getCommandWithSSHPrefix(command, environment.keys)
        val startTime = System.nanoTime()
        val result = super.exec(cmd, getEnvironmentForSSH(environment), timeOut, returnFailure, logMarker, processBuilder)
        val elapsedTime = System.nanoTime() - startTime
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedTime)
        val marker = MapEntriesAppendingMarker(
            mapOf(
                LogMarkers.HOSTNAME to remoteHost,
                LogMarkers.SSH_PROFILING_MS to elapsedMillis
            )
        )
        logger.debug(
            marker, "Execution of SSH command took $elapsedMillis ms. Command: ${cmd.joinToString(" ")}, PID: ${result.pid}")

        if (result.exitCode == SSH_ERROR) {
            // FIXME: Check stdout and stderr, if they are empty – ssh timeout, otherwise, it is likely to be command error
            val message = "Probably SSH could not connect to node $remoteHost. Result: $result"
            logger.error(logMarker, message)
            throw SshConnectionException(message)
        }

        return result
    }

    override fun startProcess(command: List<String>, environment: Map<String, String>, logMarker: Marker?, processBuilder: ProcessBuilder): Process {
        return super.startProcess(getCommandWithSSHPrefix(command, environment.keys), getEnvironmentForSSH(environment), logMarker, processBuilder)
    }

    override fun escape(value: String): String {
        return ShellUtils.escape(value)
    }

    private fun getEnvironmentForSSH(environment: Map<String, String>): HashMap<String, String> {
        val envWithSsh = HashMap<String, String>(sshEnv)
        envWithSsh.putAll(environment)
        return envWithSsh
    }

    private fun getCommandWithSSHPrefix(command: List<String>, environmentVariables: Set<String>): ArrayList<String> {
        val commandWithSshPrefix = ArrayList<String>()
        commandWithSshPrefix.addAll(listOf(
            SSH_COMMAND,
            "-o", "ConnectTimeout=$connectionTimeout",
            "-o", "PreferredAuthentications=publickey"
        ))

        environmentVariables.forEach {
            commandWithSshPrefix.add("-o")
            commandWithSshPrefix.add("SendEnv=$it")
        }

        commandWithSshPrefix.addAll(FORCE_PSEUDO_TERMINAL_ALLOCATION)

        if (isVerboseMode) {
            commandWithSshPrefix.add("-vvv")
        } else {
            commandWithSshPrefix.add(QUIET_MODE)
        }

        commandWithSshPrefix.add(userAtHost)

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
