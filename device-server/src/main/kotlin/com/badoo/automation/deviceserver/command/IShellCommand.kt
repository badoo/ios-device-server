package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.ios.proc.LongRunningProcessListener
import org.slf4j.Marker
import java.time.Duration

interface IShellCommand {
    /**
     * Will run passed shell command and return result.
     * By default any command times out after default [timeOut].
     * If you expect the command to take more than default [timeOut]
     * then specify [timeOut] for your command.
     *
     * Example: exec("/usr/local/bin/fbsimctl", listOf("--json", "list"))
     *
     * Will throw [SshConnectionException] if remote host is unreachable
     */
    fun exec(
        command: List<String>,
        environment: Map<String, String> = mapOf(),
        timeOut: Duration = Duration.ofSeconds(60),
        returnFailure: Boolean = true,
        logMarker: Marker? = null,
        processListener: IShellCommandListener = ShellCommandListener()
    ): CommandResult

    fun startProcess(
        command: List<String>,
        environment: Map<String, String>,
        logMarker: Marker? = null,
        processListener: LongRunningProcessListener
    )

    /**
     * Escape exec argument string if needed
     */
    fun escape(value: String): String // FIXME: this is temp workaround. Need to embed escaping into exec itself

}
