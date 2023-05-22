package com.badoo.automation.deviceserver.host

import XCRunSimctl
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import java.io.File
import java.time.Duration

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

        const val SSH_AUTH_SOCK = "SSH_AUTH_SOCK"
        private val asdfUserPath = File(System.getProperty("user.home"), ".asdf/shims").absolutePath
        val DEFAULT_PATH = "$asdfUserPath:/Users/qa/.asdf/shims:/usr/local/opt/appium/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Library/Apple/usr/bin"
    }

    val homeBrewPath: File
    val hostName: String
    val userName: String
    val publicHostName: String
    val localExecutor: IShellCommand
    val remoteExecutor: IShellCommand
    fun isReachable(): Boolean
    fun isLocalhost(): Boolean = isLocalhost(hostName, userName)

    fun execIgnoringErrors(command: List<String>, env: Map<String, String> = emptyMap(), timeOutSeconds: Long = 60): CommandResult
            = exec(command, env, returnFailure = true, timeOutSeconds = timeOutSeconds)

    fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult

    fun shell(command: String, returnOnFailure: Boolean = true, environment: Map<String, String> = emptyMap()) : CommandResult

    fun escape(value: String) : String

    /**
     * Returns [CommandResult] file contents
     * //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigate ByteReadChannel
     */
    fun captureFile(file: File): ByteArray

    fun pkill(matchString: String, force: Boolean)

    /**
     * Sends command to FBSimctl and expects JSON back from FBSimctl,
     *
     * @return Set<Map<String, Any>> parsed JSON
     */
    val fbsimctl: FBSimctl
    val xcrunSimctl: XCRunSimctl
    fun isDirectory(path: String): Boolean
    fun scpToRemoteHost(from: String, to: String, timeOut: Duration = Duration.ofMinutes(3))
    fun rm(path: String, timeOut: Duration = Duration.ofMinutes(3))
    fun scpFromRemoteHost(from: String, to: String, timeOut: Duration)
}
