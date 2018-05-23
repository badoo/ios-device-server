package com.badoo.automation.deviceserver.command

data class CommandResult(
    // FIXME: Separate binary and string results for "capture" and "process open" executors
    val stdOut: String,
    val stdErr: String,
    @Suppress("ArrayInDataClass") val stdOutBytes: ByteArray,
    val exitCode: Int,
    val isSuccess: Boolean = exitCode == 0,
    val cmd: List<String> = listOf()
)
