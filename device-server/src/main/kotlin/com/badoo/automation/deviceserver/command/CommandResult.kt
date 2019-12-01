package com.badoo.automation.deviceserver.command

import java.lang.StringBuilder

private const val MAX_CHARS = 1024

data class CommandResult(
    // FIXME: Separate binary and string results for "capture" and "process open" executors
    val stdOut: String,
    val stdErr: String,
    val exitCode: Int,
    val isSuccess: Boolean = exitCode == 0,
    val cmd: List<String> = listOf(),
    val pid: Long
) {
    override fun toString(): String {
        val sb = StringBuilder(javaClass.simpleName)
        sb.append("(")
        sb.append("cmd=$cmd")
        sb.append(", ")
        sb.append("exitCode=$exitCode")
        sb.append(", ")
        sb.append("stdOut=${stdOut.take(MAX_CHARS)}")
        sb.append(", ")
        sb.append("stdErr=${stdErr.take(MAX_CHARS)}")
        sb.append(")")
        return sb.toString()
    }
}
