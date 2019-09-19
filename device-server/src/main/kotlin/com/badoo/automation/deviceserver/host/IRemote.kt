package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import java.io.File

interface IRemote {
    companion object {
        private const val LOCALHOST = "localhost"
        private const val LOCALHOST_NET_PREFIX = "127."
        fun isLocalhost(hostName: String, userName: String): Boolean {
            if (userName.isNotBlank()) {
                return false // Use ssh if user was specified explicitly
            }

            return hostName == LOCALHOST || hostName.startsWith(LOCALHOST_NET_PREFIX)
        }
    }

    val hostName: String
    val userName: String
    val publicHostName: String
    val localExecutor: IShellCommand
    fun isReachable(): Boolean
    fun isLocalhost(): Boolean = isLocalhost(hostName, userName)

    fun execIgnoringErrors(command: List<String>, env: Map<String, String> = emptyMap(), timeOutSeconds: Long = 30): CommandResult
            = exec(command, env, returnFailure = true, timeOutSeconds = timeOutSeconds)

    fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult

    fun shell(command: String, returnOnFailure: Boolean = true) : CommandResult

    fun escape(value: String) : String

    /**
     * Returns [CommandResult] file contents would be in [CommandResult.stdOutBytes]
     * //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigate ByteReadChannel
     */
    fun captureFile(file: File): CommandResult

    fun pkill(matchString: String, force: Boolean)

    /**
     * Sends command to FBSimctl and expects JSON back from FBSimctl,
     *
     * @return Set<Map<String, Any>> parsed JSON
     */
    val fbsimctl: FBSimctl
    fun isDirectory(path: String): Boolean
    fun rsync(from: String, to: String, flags: Set<String>)
}
