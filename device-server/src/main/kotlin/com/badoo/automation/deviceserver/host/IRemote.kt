package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import java.io.File
import java.time.Duration

interface IRemote {
    val homeBrewPath: File
    val tmpDir: File
    val hostName: String
    val publicHostName: String
    val localExecutor: IShellCommand
    val remoteExecutor: IShellCommand
    fun isReachable(): Boolean

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

    fun pkill(matchString: String, force: Boolean): CommandResult

    /**
     * Sends command to FBSimctl and expects JSON back from FBSimctl,
     *
     * @return Set<Map<String, Any>> parsed JSON
     */
    val fbsimctl: FBSimctl
    fun isDirectory(path: String): Boolean
    fun rm(path: String, timeOut: Duration = Duration.ofMinutes(3))
}
